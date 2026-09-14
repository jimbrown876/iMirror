package dev.imirror.receiver.airplay

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import dev.imirror.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * Methods are `@Synchronized` because RTSP control verbs arrive on the RTSP thread while MediaPlayer
 * callbacks fire on its own thread.
 */
class AirPlayVideoPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
) {
    private var mp: MediaPlayer? = null
    @Volatile private var prepared = false
    @Volatile private var startFraction = 0.0
    private var surfaceJob: Job? = null
    private var wantsPlayback = true

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once prepared. */
    @Synchronized
    fun play(url: String, startPositionFraction: Double) {
        release()
        startFraction = startPositionFraction.coerceIn(0.0, 1.0)
        wantsPlayback = true
        Logger.i("AirPlay video: play url=$url start=$startFraction")
        val player = MediaPlayer()
        mp = player
        prepared = false
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            surfaceProvider()?.takeIf { it.isValid }?.let { player.setSurface(it) }
            player.setOnPreparedListener { onPrepared(it) }
            player.setOnCompletionListener { completed ->
                synchronized(this) {
                    if (mp === completed) { Logger.i("AirPlay video: completed"); onEnded() }
                }
            }
            player.setOnErrorListener { failed, what, extra ->
                synchronized(this) {
                    if (mp === failed) Logger.e("AirPlay video error what=$what extra=$extra")
                }
                true   // handled — don't also fire onCompletion
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure { Logger.e("AirPlay video: setup failed", it); release() }
    }

    @Synchronized
    private fun onPrepared(player: MediaPlayer) {
        // onPrepared fires on MediaPlayer's own thread and can race release()/a new play(): if the
        // player was released (or replaced) while preparing, mp no longer points at it — bail before
        // touching a dead MediaPlayer (which would throw IllegalStateException).
        if (mp !== player) return
        surfaceJob?.cancel()
        // Preparation can finish before Activity takeover creates a Surface. Suspend while waiting,
        // and re-check player identity so stop/replacement cannot start a stale video afterwards.
        surfaceJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            val surface = awaitValidOutput(
                provider = surfaceProvider,
                valid = { it.isValid },
                current = { synchronized(this@AirPlayVideoPlayer) { mp === player } }
            )
            synchronized(this@AirPlayVideoPlayer) {
                if (mp !== player) return@launch
                if (surface == null) {
                    Logger.w("AirPlay URL video: no valid surface after 5 seconds — ending pending playback")
                    release()
                    onEnded()
                    return@launch
                }
                runCatching {
                    player.setSurface(surface)
                    if (startFraction > 0.0) player.seekTo((startFraction * player.duration).toInt())
                    prepared = true
                    if (wantsPlayback) player.start()
                    Logger.i("AirPlay URL video: valid surface attached; ${if (wantsPlayback) "playing" else "paused"}")
                }.onFailure { Logger.e("AirPlay video: output startup failed", it); release() }
            }
        }
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    @Synchronized
    fun setRate(rate: Float) {
        wantsPlayback = rate > 0f
        val player = mp ?: return
        if (!prepared) return
        runCatching {
            if (rate <= 0f) { if (player.isPlaying) player.pause() }
            else { if (!player.isPlaying) player.start() }
        }
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    fun scrub(positionSec: Double) {
        runCatching { mp?.seekTo((positionSec * 1000).toInt()) }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    @Synchronized
    fun info(): PlaybackInfo? {
        val player = mp ?: return null
        if (!prepared) return PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        val dur = runCatching { player.duration }.getOrDefault(0)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        return PlaybackInfo(dur / 1000.0, pos / 1000.0, if (playing) 1.0 else 0.0, readyToPlay = true)
    }

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    @Synchronized
    fun attachSurface() {
        surfaceProvider()?.takeIf { it.isValid }?.let { runCatching { mp?.setSurface(it) } }
    }

    @Synchronized
    fun release() {
        surfaceJob?.cancel()
        surfaceJob = null
        val player = mp
        mp = null
        prepared = false
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
    }
}
