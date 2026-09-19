package dev.imirror.receiver.airplay

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import dev.imirror.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
    val completed: Boolean = false,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * Android requires creation and all MediaPlayer access on the same Looper thread. Commands and
 * callbacks are serialized on Main; network threads read an immutable, periodically refreshed snapshot.
 */
class AirPlayVideoPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onFailure: () -> Unit = {},
    private val playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val playerFactory: () -> MediaPlayer = { MediaPlayer() },
) {
    private val scope = CoroutineScope(SupervisorJob() + playerDispatcher)
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var completed = false
    private var startFraction = 0.0
    private var pendingSeekSec: Double? = null
    private var surfaceJob: Job? = null
    private var snapshotJob: Job? = null
    private var wantsPlayback = true
    @Volatile private var snapshot: PlaybackInfo? = null
    private val queuedPlays = AtomicInteger(0)

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once prepared. */
    fun play(url: String, startPositionFraction: Double) {
        queuedPlays.incrementAndGet()
        scope.launch {
            try { playOnThread(url, startPositionFraction) }
            finally { queuedPlays.decrementAndGet() }
        }
    }

    private fun playOnThread(url: String, startPositionFraction: Double) {
        releaseOnThread()
        startFraction = startPositionFraction.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        wantsPlayback = true
        Logger.i("AirPlay video: preparing URL media start=$startFraction")
        val player = runCatching { playerFactory() }.getOrElse {
            Logger.e("AirPlay video: player creation failed", it)
            onFailure()
            return
        }
        mp = player
        snapshot = PlaybackInfo(0.0, 0.0, 0.0, false)
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            surfaceProvider()?.takeIf { it.isValid }?.let { player.setSurface(it) }
            player.setOnPreparedListener { ready -> scope.launch { onPrepared(ready) } }
            player.setOnCompletionListener { finished ->
                scope.launch {
                    if (mp === finished) {
                        completed = true
                        wantsPlayback = false
                        updateSnapshot(finished)
                        Logger.i("AirPlay video: completed; parent route retained")
                    }
                }
            }
            player.setOnErrorListener { failed, what, extra ->
                scope.launch {
                    if (mp === failed) {
                        Logger.e("AirPlay video error what=$what extra=$extra")
                        failCurrent(failed)
                    }
                }
                true   // handled — don't also fire onCompletion
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure { Logger.e("AirPlay video: setup failed", it); failCurrent(player) }
    }

    private fun onPrepared(player: MediaPlayer) {
        // A queued callback can follow release()/a new play(). If the player was replaced while
        // preparing, mp no longer points at it — bail before
        // touching a dead MediaPlayer (which would throw IllegalStateException).
        if (mp !== player) return
        surfaceJob?.cancel()
        // Preparation can finish before Activity takeover creates a Surface. Suspend while waiting,
        // and re-check player identity so stop/replacement cannot start a stale video afterwards.
        surfaceJob = scope.launch {
            val surface = awaitValidOutput(
                provider = surfaceProvider,
                valid = { it.isValid },
                current = { mp === player }
            )
            if (mp !== player) return@launch
            if (surface == null) {
                Logger.w("AirPlay URL video: no valid surface after 5 seconds — ending pending playback")
                failCurrent(player)
                return@launch
            }
            runCatching {
                player.setSurface(surface)
                val seekMs = pendingSeekSec?.let { it * 1000.0 } ?: (startFraction * player.duration)
                if (seekMs > 0.0) player.seekTo(seekMs.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())
                pendingSeekSec = null
                prepared = true
                if (wantsPlayback) player.start()
                updateSnapshot(player)
                snapshotJob?.cancel()
                snapshotJob = scope.launch {
                    while (mp === player) { delay(100); if (mp === player) updateSnapshot(player) }
                }
                Logger.i("AirPlay URL video: valid surface attached; ${if (wantsPlayback) "playing" else "paused"}")
            }.onFailure { Logger.e("AirPlay video: output startup failed", it); failCurrent(player) }
        }
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    fun setRate(rate: Float) {
        if (!rate.isFinite()) return
        scope.launch {
            wantsPlayback = rate > 0f
            val player = mp ?: return@launch
            if (!prepared) return@launch
            runCatching {
                if (rate <= 0f) { if (player.isPlaying) player.pause() }
                else { completed = false; if (!player.isPlaying) player.start() }
                updateSnapshot(player)
            }.onFailure { failCurrent(player) }
        }
    }

    /** Seeks to [positionSec] seconds. */
    fun scrub(positionSec: Double) {
        if (!positionSec.isFinite() || positionSec < 0.0) return
        scope.launch {
            val player = mp ?: return@launch
            if (!prepared) { pendingSeekSec = positionSec; return@launch }
            runCatching {
                completed = false
                player.seekTo((positionSec * 1000).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())
                updateSnapshot(player)
            }.onFailure { failCurrent(player) }
        }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    fun info(): PlaybackInfo? = if (queuedPlays.get() > 0) PlaybackInfo(0.0, 0.0, 0.0, false) else snapshot

    private fun updateSnapshot(player: MediaPlayer) {
        if (mp !== player || !prepared) return
        val dur = runCatching { player.duration }.getOrDefault(0)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        snapshot = PlaybackInfo(dur / 1000.0, pos / 1000.0, if (playing && !completed) 1.0 else 0.0,
            readyToPlay = true, completed = completed)
    }

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    fun attachSurface() {
        scope.launch {
            surfaceProvider()?.takeIf { it.isValid }?.let { runCatching { mp?.setSurface(it) } }
        }
    }

    fun release() {
        scope.launch { releaseOnThread() }
    }

    private fun failCurrent(player: MediaPlayer) {
        if (mp !== player) return
        releaseOnThread()
        onFailure()
    }

    private fun releaseOnThread() {
        surfaceJob?.cancel()
        surfaceJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        val player = mp
        mp = null
        prepared = false
        completed = false
        pendingSeekSec = null
        snapshot = null
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
    }
}
