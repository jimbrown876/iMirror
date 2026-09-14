package dev.imirror.receiver.airplay

import android.content.Context
import android.view.Surface
import dev.imirror.receiver.airplay.handshake.AirPlayNtpClient
import dev.imirror.receiver.airplay.handshake.AudioStreamServer
import dev.imirror.receiver.airplay.handshake.BufferedAudioServer
import dev.imirror.receiver.airplay.handshake.MirrorStreamServer
import dev.imirror.receiver.service.ProtocolState
import dev.imirror.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [MirrorService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [MirrorService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val audioEnabled: Boolean = false,
    /** Require HomeKit-style SRP PIN pairing before streaming (AppSettings.airPlayPinAuthEnabled). */
    private val pinAuthEnabled: Boolean = false,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([MirrorService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, Apple Music, podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    /** Pairing PIN to show ([pin]) or hide (null) on the TV during SRP pair-setup. */
    private val onPinChanged: (pin: String?) -> Unit = {}
) {

    // Persistent store of paired controllers (for PIN access control / pair-verify).
    private val pairingStore = dev.imirror.receiver.airplay.handshake.PairingStore(context)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val legacyStartupLock = Any()
    @Volatile private var legacyStartupGeneration = 0L
    private var legacyStartupJob: Job? = null
    private val mediaSession = MediaSessionGate()

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var videoDecoder: VideoDecoder? = null
    @Volatile private var audioPlayer: AudioPlayer? = null
    @Volatile private var senderVolumeDb = 0f

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false
    @Volatile private var npSenderName = "AirPlay"
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        scope.launch {
            try {
                startTimingHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [MirrorService] stops or is destroyed.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        mediaSession.reset()
        invalidateLegacyStartup()
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            mdnsService?.stop()
            dacpClient.stop()
            releaseMediaComponents()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    /** True once a sender has advertised DACP reverse-control (so the TV remote can drive playback). */
    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            onStateChange = { state ->
                // NSD registration callbacks can arrive after playback has already connected.
                // Never let that stale ADVERTISING edge dismiss the playback UI or its audio focus.
                if (!mediaSession.shouldPublish(state)) {
                    Logger.d("Ignoring late mDNS ADVERTISING state during active playback")
                } else {
                    emitState(state)
                }
            },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session ->
                mediaSession.activate()
                onStreamingStarted(session)
            },
            onStreamingStopped = { onStreamingStopped() },
            onPhotoReceived = { bytes, imageType ->
                mediaSession.activate()
                onPhotoReceived(bytes, imageType)
            },
            onPhotoCleared = { onPhotoCleared() },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                mediaSession.activate()
                startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { streamConnectionId -> startMirrorStream(streamConnectionId) },
            onMirrorAudioStart = { sampleRate, channels, ct, spf -> startMirrorAudio(sampleRate, channels, ct, spf) },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { stopMirrorVideo() },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onVolume = { v -> updateAudioVolume(v) },
            onNowPlayingMetadata = { title, artist, album ->
                npTitle = title; npArtist = artist; npAlbum = album
                emitNowPlaying()
            },
            onArtwork = { bytes ->
                npArtwork = bytes.takeIf { it.isNotEmpty() }
                emitNowPlaying()
            },
            onVideoPlay = { url, start ->
                mediaSession.activate()
                startUrlVideo(url, start)
            },
            onVideoRate = { rate -> urlVideoPlayer?.setRate(rate) },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoStop = { stopUrlVideo() },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) },
            hasRecentMediaActivity = { StreamStats.hasRecentMediaPacket(5_000L) }
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")

        val generation = synchronized(legacyStartupLock) {
            legacyStartupJob?.cancel()
            ++legacyStartupGeneration
        }
        val startup = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Take over first: a background receiver has no Activity/Surface until CONNECTED.
                // Awaiting this dispatch (rather than scheduling another child) preserves order.
                withContext(Dispatchers.Main) {
                    if (generation != legacyStartupGeneration) return@withContext
                    npSenderName = session.senderName.ifBlank { npSenderName }
                    videoPlaying = session.hasVideo
                    audioPlaying = session.hasAudio
                    emitNowPlaying()
                    onSenderNameChanged(session.senderName)
                    if (generation == legacyStartupGeneration) onStateChanged(ProtocolState.CONNECTED)
                }
                synchronized(legacyStartupLock) {
                    if (generation != legacyStartupGeneration) return@launch
                    // Audio-only playback never waits for a video output.
                    if (session.hasAudio) startAudioPlayer(session)
                }
                if (session.hasVideo) {
                    val surface = awaitValidOutput(
                        provider = videoSurfaceProvider,
                        valid = { it.isValid },
                        current = { generation == legacyStartupGeneration }
                    )
                    synchronized(legacyStartupLock) {
                        if (generation != legacyStartupGeneration) return@launch
                        if (surface != null) startVideoDecoder(session, surface)
                        else Logger.w("Legacy video: no valid surface after 5 seconds; audio/control remain available")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                withContext(Dispatchers.Main) {
                    if (generation == legacyStartupGeneration) onStateChanged(ProtocolState.ERROR)
                }
            }
        }
        synchronized(legacyStartupLock) {
            if (generation == legacyStartupGeneration) legacyStartupJob = startup else startup.cancel()
        }
        startup.start()
    }

    private fun invalidateLegacyStartup() = synchronized(legacyStartupLock) {
        legacyStartupGeneration++
        legacyStartupJob?.cancel()
        legacyStartupJob = null
    }

    /**
     * Called when streaming ends (TEARDOWN received or socket closed).
     *
     * Releases media components and re-advertises so the device reappears
     * in sender pickers immediately.
     */
    private fun onStreamingStopped() {
        if (!mediaSession.endOnce()) {
            Logger.d("Ignoring duplicate or discovery-only AirPlay disconnect")
            return
        }
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        emitState(ProtocolState.ADVERTISING)
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription, surface: Surface) {
        if (!surface.isValid) return
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder?.release()
        videoDecoder = VideoDecoder(surface).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                decoder.decodeNalUnit(nalUnit, ptsUs)
            }
        }
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription) {
        audioSocket?.close()
        audioSocket = null
        audioPlayer?.release()
        val player = AudioPlayer()
        audioPlayer = player
        player.setVolume(senderVolumeDb)
        try {
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        } catch (e: Exception) {
            player.release()
            if (audioPlayer === player) audioPlayer = null
            throw e
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver(player)
    }

    private fun updateAudioVolume(volume: Float) {
        if (!volume.isFinite()) return
        senderVolumeDb = volume
        audioPlayer?.setVolume(volume)
        audioServer?.setVolume(volume)
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver(player: AudioPlayer) {
        // Bind before launching so a later teardown cannot be overtaken by delayed socket setup.
        val socket = DatagramSocket(AUDIO_RTP_PORT)
        audioSocket = socket
        scope.launch(Dispatchers.IO) {
            try {
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive && audioSocket === socket && !socket.isClosed) {
                    receiveAudioDatagram(socket, packet)
                    StreamStats.markMediaPacket()
                    // copyOf trims to actual packet length before passing to the player
                    player.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // SocketException thrown when audioSocket.close() is called — expected
                if (!socket.isClosed && audioSocket === socket) {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                } else {
                    Logger.d("Audio socket closed (expected during shutdown)")
                }
            } finally {
                socket.close()
                if (audioSocket === socket) audioSocket = null
            }
        }
    }

    // ─── Private: AirPlay 2 mirroring ─────────────────────────────────────────

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        val event = ServerSocket(0)
        eventSocket = event
        // Accept + drain the event connection. We don't act on events yet, but macOS expects
        // the advertised event port to be connectable, so keep it open and readable.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { /* drain */ }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    private fun startMirrorStream(streamConnectionId: Long): Int {
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        return MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight)
            .also { mirrorServer = it; it.start(scope); videoPlaying = true; emitNowPlaying() }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    private fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket)
            .also { audioServer = it; it.setVolume(senderVolumeDb); it.start(scope) }
        audioPlaying = true
        emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    private fun stopMirrorVideo() {
        mirrorServer?.stop()
        mirrorServer = null
        videoPlaying = false
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /**
     * AirPlay video URL mode (non-mirroring): show the streaming surface and hand the URL to
     * [AirPlayVideoPlayer], which fetches + plays it via MediaPlayer onto the same Surface.
     */
    private fun startUrlVideo(url: String, startFraction: Double) {
        invalidateLegacyStartup()
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)   // shows StreamingScreen → Surface becomes available
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            surfaceProvider = videoSurfaceProvider,
            onEnded = { stopUrlVideo() }
        ).also { urlVideoPlayer = it }
        player.play(url, startFraction)
        Logger.i("AirPlay URL video started: $url (start=$startFraction)")
    }

    /** Stops AirPlay video URL playback (POST /stop or end-of-media) and ends the session. */
    private fun stopUrlVideo() {
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    private fun startBufferedAudio(): Int {
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        audioPlaying = true   // buffered audio (type 103) is always audio-only
        emitNowPlaying()
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private fun stopBufferedAudio() {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /** Clears the video NAL callback, closes the audio socket, and releases media components. */
    private fun releaseMediaComponents() {
        invalidateLegacyStartup()
        rtspHandler?.onVideoNalUnit = null
        try { audioSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioSocket = null
        mirrorServer?.stop()
        mirrorServer = null
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventClientSocket = null
        try { eventSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventSocket = null
        // Clear the FairPlay/ECDH keys on FULL teardown only. This method runs on a genuine session
        // end (last-stream / session TEARDOWN, or control-connection close) — NOT on a per-stream
        // teardown, which goes through stopMirrorAudio/stopMirrorVideo and leaves the keys intact so
        // macOS can re-add a dynamic stream on the same live session without re-sending keys (that
        // dynamic-readd path is why the keys must survive a stream stop). Clearing here prevents a
        // brand-new control connection from reusing a previous session's stale keys.
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
        StreamStats.resetStreams()
        // A photo session has no decoder/server to clear its UI state. Always clear the frame on
        // full teardown so the temporary TV takeover and audio focus can return to the prior app.
        onPhotoCleared()
        // Session fully torn down — clear now-playing so the UI leaves the audio card.
        audioPlaying = false
        videoPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
    }

    /** Pushes the current now-playing state out: a [NowPlayingInfo] when audio plays without video, else null. */
    private fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(npSenderName, npTitle, npArtist, npAlbum, npArtwork) else null
        )
    }

    /** Drops stale track metadata/artwork when an audio stream ends (so it can't bleed into the next). */
    private fun clearNowPlayingMetadata() {
        npTitle = null; npArtist = null; npAlbum = null; npArtwork = null
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }

    companion object {
        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}

/** receive() replaces length with the received size; restore capacity before reusing the packet. */
internal fun receiveAudioDatagram(socket: DatagramSocket, packet: DatagramPacket) {
    packet.length = packet.data.size - packet.offset
    socket.receive(packet)
}

/** A bounded, cancellable wait shared by legacy and URL video startup; no RTSP/main-thread sleeps. */
internal suspend fun <T : Any> awaitValidOutput(
    provider: () -> T?,
    valid: (T) -> Boolean,
    current: () -> Boolean,
    timeoutMs: Long = 5_000,
    pollMs: Long = 50
): T? = withTimeoutOrNull(timeoutMs) {
    require(pollMs > 0)
    while (current()) {
        provider()?.takeIf(valid)?.let { return@withTimeoutOrNull it }
        delay(pollMs)
    }
    null
}

/** Serializes the visible media lifecycle across duplicated RTSP and asynchronous NSD callbacks. */
internal class MediaSessionGate {
    private val active = AtomicBoolean(false)

    fun activate() { active.set(true) }
    fun reset() { active.set(false) }
    fun endOnce(): Boolean = active.compareAndSet(true, false)
    fun shouldPublish(state: ProtocolState): Boolean =
        state != ProtocolState.ADVERTISING || !active.get()
}
