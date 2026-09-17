package dev.imirror.receiver.airplay.handshake

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import dev.imirror.receiver.airplay.audioRtpPayloadRange
import dev.imirror.receiver.airplay.airplayVolumeGain
import dev.imirror.receiver.airplay.applyPcm16LeGainInPlace
import dev.imirror.receiver.airplay.StreamStats
import dev.imirror.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private sealed interface QueuedAudioFrame {
    val generation: Long
    data class Encoded(val bytes: ByteArray, override val generation: Long) : QueuedAudioFrame
    data class Silence(override val generation: Long) : QueuedAudioFrame
}

internal data class ResendRange(val startSeq: Int, val count: Int)

internal class AudioSinkException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun requireAudioWriteResult(result: Int): Int {
    if (result <= 0) throw AudioSinkException("AudioTrack write failed: $result")
    return result
}

/** Converts platform write exceptions into the fatal sink signal used by the session lifecycle. */
internal fun guardedAudioWrite(write: () -> Int): Int = try {
    write()
} catch (e: AudioSinkException) {
    throw e
} catch (e: RuntimeException) {
    throw AudioSinkException("AudioTrack write threw", e)
}

/** Bounded decoder-health state: brief corruption is concealed; persistent silence is fatal. */
internal class DecoderHealthPolicy(
    private val maximumConsecutiveFailures: Int = 8,
    private val maximumFramesWithoutPcm: Int = 32
) {
    private var consecutiveFailures = 0
    private var framesWithoutPcm = 0

    init {
        require(maximumConsecutiveFailures > 0 && maximumFramesWithoutPcm >= maximumConsecutiveFailures)
    }

    fun onPcmProduced() {
        consecutiveFailures = 0
        framesWithoutPcm = 0
    }

    fun onFrameWithoutPcm(): Boolean {
        consecutiveFailures = 0
        framesWithoutPcm++
        return framesWithoutPcm >= maximumFramesWithoutPcm
    }

    fun onDecodeFailure(): Boolean {
        consecutiveFailures++
        framesWithoutPcm++
        return consecutiveFailures >= maximumConsecutiveFailures ||
            framesWithoutPcm >= maximumFramesWithoutPcm
    }

    fun reset() {
        consecutiveFailures = 0
        framesWithoutPcm = 0
    }
}

internal fun buildFadedPcmSilence(
    lastSamples: ShortArray,
    frames: Int,
    sampleRate: Int
): ByteArray {
    require(lastSamples.isNotEmpty() && frames > 0 && sampleRate > 0)
    val channels = lastSamples.size
    val output = ByteArray(frames * channels * 2)
    val fadeFrames = minOf(frames, maxOf(1, sampleRate / 200)) // 5 ms
    for (frame in 0 until fadeFrames) {
        val remaining = fadeFrames - frame
        for (channel in 0 until channels) {
            val sample = (lastSamples[channel].toInt() * remaining / fadeFrames).toShort()
            val offset = (frame * channels + channel) * 2
            output[offset] = sample.toInt().toByte()
            output[offset + 1] = (sample.toInt() shr 8).toByte()
        }
    }
    return output
}

internal fun applyPcmFadeInInPlace(pcm: ByteArray, channels: Int, sampleRate: Int): ByteArray {
    require(channels > 0 && sampleRate > 0 && pcm.size % (channels * 2) == 0)
    val frames = pcm.size / (channels * 2)
    val fadeFrames = minOf(frames, maxOf(1, sampleRate / 200))
    for (frame in 0 until fadeFrames) {
        val gainNumerator = frame + 1
        for (channel in 0 until channels) {
            val offset = (frame * channels + channel) * 2
            val raw = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
            val sample = (raw.toShort().toInt() * gainNumerator / fadeFrames).toShort()
            pcm[offset] = sample.toInt().toByte()
            pcm[offset + 1] = (sample.toInt() shr 8).toByte()
        }
    }
    return pcm
}

/** One outstanding RTP gap with a fixed retry ceiling, independent of packet arrival cadence. */
internal class ResendRetryPolicy(
    private val retryIntervalNanos: Long = 30_000_000L,
    private val maxAttempts: Int = 3,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private var pendingStart = -1
    private var pendingCount = 0
    private var attempts = 0
    private var lastRequestNanos = Long.MIN_VALUE

    fun request(startSeq: Int, count: Int): ResendRange? {
        if (count !in 1..128) {
            clear()
            return null
        }
        val normalizedStart = startSeq and 0xFFFF
        if (normalizedStart != pendingStart) {
            pendingStart = normalizedStart
            pendingCount = count
            attempts = 0
            lastRequestNanos = Long.MIN_VALUE
        } else {
            // Recompute the first contiguous hole on every pass. Packets can arrive out of order,
            // so retaining an older, wider count needlessly asks the sender to resend data we have.
            pendingCount = count
        }
        val now = clockNanos()
        val due = lastRequestNanos == Long.MIN_VALUE || now - lastRequestNanos >= retryIntervalNanos
        if (attempts >= maxAttempts || !due) return null
        attempts++
        lastRequestNanos = now
        return ResendRange(pendingStart, pendingCount)
    }

    fun resolved(nextExpectedSeq: Int) {
        if (pendingStart >= 0 && sequenceDistance(nextExpectedSeq, pendingStart) > 0) clear()
    }

    fun clear() {
        pendingStart = -1
        pendingCount = 0
        attempts = 0
        lastRequestNanos = Long.MIN_VALUE
    }

    private fun sequenceDistance(a: Int, b: Int): Int =
        (((a - b) and 0xFFFF) xor 0x8000) - 0x8000
}

/** Builds and sends RAOP resend requests to the endpoint explicitly advertised by the sender. */
internal class RaopResendChannel(
    initialTarget: SocketAddress?,
    private val expectedPeer: InetAddress? = (initialTarget as? InetSocketAddress)?.address,
    private val sendPacket: (ByteArray, SocketAddress) -> Unit
) {
    private val advertisedTarget: SocketAddress? = initialTarget
    @Volatile private var target: SocketAddress? = initialTarget
    private var observedFallback: SocketAddress? = null
    private var confirmedTarget: SocketAddress? = null
    private var outstandingGapStart = -1
    private var unansweredAdvertisedRequests = 0
    private var sequence = 0
    @Volatile var successfulRequests: Int = 0
        private set
    @Volatile var failedRequests: Int = 0
        private set

    @Synchronized
    fun learnFallback(address: SocketAddress): Boolean {
        if (!matchesExpectedPeer(address)) return false
        val current = target
        if (current == null) {
            target = address
            observedFallback = address
            return true
        }
        if (current != address && samePeer(current, address)) observedFallback = address
        return false
    }

    fun isReady(): Boolean = target != null

    @Synchronized
    fun request(range: ResendRange): Boolean {
        if (range.startSeq != outstandingGapStart) {
            outstandingGapStart = range.startSeq
            unansweredAdvertisedRequests = 0
            target = confirmedTarget ?: advertisedTarget ?: observedFallback
        }
        if (target == advertisedTarget &&
            unansweredAdvertisedRequests >= ADVERTISED_ATTEMPTS_BEFORE_FALLBACK) {
            observedFallback?.let { target = it }
        }
        val destination = target ?: return false
        val packet = buildPacket(sequence, range)
        sequence = (sequence + 1) and 0xFFFF
        return runCatching {
            sendPacket(packet, destination)
            if (destination == advertisedTarget) unansweredAdvertisedRequests++
            successfulRequests++
            true
        }.getOrElse {
            if (destination == advertisedTarget) unansweredAdvertisedRequests++
            failedRequests++
            false
        }
    }

    @Synchronized
    fun confirmReplySource(address: SocketAddress) {
        if (!matchesExpectedPeer(address)) return
        val current = target
        if (current == null || samePeer(current, address)) {
            target = address
            confirmedTarget = address
            unansweredAdvertisedRequests = 0
            outstandingGapStart = -1
        }
    }

    internal fun targetForTest(): SocketAddress? = target

    private fun matchesExpectedPeer(address: SocketAddress): Boolean {
        val inet = address as? InetSocketAddress ?: return expectedPeer == null
        return expectedPeer == null || inet.address == expectedPeer
    }

    companion object {
        private const val ADVERTISED_ATTEMPTS_BEFORE_FALLBACK = 2

        private fun samePeer(a: SocketAddress, b: SocketAddress): Boolean {
            val left = a as? InetSocketAddress ?: return a == b
            val right = b as? InetSocketAddress ?: return a == b
            return left.address == right.address
        }

        internal fun buildPacket(requestSequence: Int, range: ResendRange): ByteArray = ByteArray(8).apply {
            this[0] = 0x80.toByte()
            this[1] = 0xD5.toByte()
            this[2] = (requestSequence ushr 8).toByte()
            this[3] = requestSequence.toByte()
            this[4] = (range.startSeq ushr 8).toByte()
            this[5] = range.startSeq.toByte()
            this[6] = (range.count ushr 8).toByte()
            this[7] = range.count.toByte()
        }
    }
}

/** Pure state machine for priming and bounded adaptive rebuffering of music-only playback. */
internal class MusicPlayoutPolicy(
    initialPrimeBytes: Int,
    private val maximumPrimeBytes: Int,
    private val rebufferStepBytes: Int
) {
    var targetPrimeBytes: Int = initialPrimeBytes
        private set
    var started: Boolean = false
        private set
    private var primedBytes = 0
    private var observedUnderruns = 0

    fun setUnderrunBaseline(count: Int) {
        observedUnderruns = count.coerceAtLeast(0)
    }

    /** Returns true exactly when the caller should transition AudioTrack to playing. */
    fun onBytesWritten(count: Int): Boolean {
        if (started) return false
        primedBytes += count.coerceAtLeast(0)
        if (primedBytes < targetPrimeBytes) return false
        started = true
        return true
    }

    /** Avoid a stopped-track deadlock if the platform accepts less than its requested capacity. */
    fun startWithPrimedData(): Boolean {
        if (started || primedBytes <= 0) return false
        started = true
        return true
    }

    /** Returns true when a new platform underrun requires pause/flush/re-prime. */
    fun onUnderrunCount(count: Int): Boolean {
        if (count <= observedUnderruns) return false
        observedUnderruns = count
        if (!started) return false
        started = false
        primedBytes = 0
        targetPrimeBytes = (targetPrimeBytes + rebufferStepBytes).coerceAtMost(maximumPrimeBytes)
        return true
    }

    fun reset() {
        started = false
        primedBytes = 0
    }
}

/**
 * Rejects UDP packets that were already in flight when FLUSH/PAUSE cleared playback. A protocol
 * RTP-Info boundary is preferred; without one, the next sequence/timestamp after the newest packet
 * already seen becomes the floor. The gate disarms on the first valid post-flush packet, after which
 * the normal reorderer rejects late traffic.
 */
internal class PostFlushIngressGate {
    private var latestSequence = -1
    private var latestTimestamp = -1L
    private var minimumSequence = -1
    private var minimumTimestamp = -1L
    private var acceptNextAsNewEpoch = false

    fun arm(sequence: Int?, timestamp: Long?) {
        minimumSequence = sequence?.and(0xFFFF)
            ?: latestSequence.takeIf { it >= 0 }?.let { (it + 1) and 0xFFFF }
            ?: -1
        minimumTimestamp = timestamp?.and(0xFFFF_FFFFL)
            ?: latestTimestamp.takeIf { it >= 0 }?.let { (it + 1) and 0xFFFF_FFFFL }
            ?: -1L
        acceptNextAsNewEpoch = false
    }

    fun accept(sequence: Int, timestamp: Long): Boolean {
        val seq = sequence and 0xFFFF
        val time = timestamp and 0xFFFF_FFFFL
        if (acceptNextAsNewEpoch) {
            reanchor(seq, time)
            return true
        }
        if (minimumSequence >= 0 && sequenceDistance(seq, minimumSequence) < 0) return false
        if (minimumTimestamp >= 0 && timestampDistance(time, minimumTimestamp) < 0) return false
        minimumSequence = -1
        minimumTimestamp = -1L
        if (latestSequence < 0 || sequenceDistance(seq, latestSequence) > 0) latestSequence = seq
        if (latestTimestamp < 0 || timestampDistance(time, latestTimestamp) > 0) latestTimestamp = time
        return true
    }

    /** A negotiated sender restart establishes a new sequence/timestamp epoch. */
    fun reanchor(sequence: Int, timestamp: Long) {
        latestSequence = sequence and 0xFFFF
        latestTimestamp = timestamp and 0xFFFF_FFFFL
        minimumSequence = -1
        minimumTimestamp = -1L
        acceptNextAsNewEpoch = false
    }

    /** PAUSE drains UDP; without a RECORD boundary, the first resumed packet defines the epoch. */
    fun acceptNextEpoch() {
        minimumSequence = -1
        minimumTimestamp = -1L
        acceptNextAsNewEpoch = true
    }

    private fun sequenceDistance(a: Int, b: Int): Int =
        (((a - b) and 0xFFFF) xor 0x8000) - 0x8000

    private fun timestampDistance(a: Long, b: Long): Long =
        (((a - b) and 0xFFFF_FFFFL) xor 0x8000_0000L) - 0x8000_0000L
}

internal sealed interface ReorderedPacket {
    data class Encoded(val bytes: ByteArray) : ReorderedPacket
    data object Concealment : ReorderedPacket
}

internal data class ReorderOutput(
    val playoutFrames: List<ReorderedPacket>,
    val gap: ResendRange?,
    val nextExpectedSeq: Int,
    val duplicateOrLate: Boolean = false,
    val expiredGap: Boolean = false,
    val resynchronized: Boolean = false
) {
    val encodedFrames: List<ByteArray>
        get() = playoutFrames.filterIsInstance<ReorderedPacket.Encoded>().map { it.bytes }
    val silencePackets: Int
        get() = playoutFrames.count { it is ReorderedPacket.Concealment }
}

/**
 * Deterministic RTP sequence reorderer. It bounds both packet-distance and wall-clock waiting so a
 * single lost packet at pause/end-of-track cannot strand every valid packet behind it forever.
 */
internal class RtpReorderBuffer(
    private val maximumTrackedPackets: Int = 128,
    private val holdNanos: Long,
    private val maximumConcealmentPackets: Int = DEFAULT_MAXIMUM_CONCEALMENT_PACKETS,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private val packets = HashMap<Int, ByteArray>()
    private var nextSeq = -1
    private var maxSeq = -1
    private var latestTimestamp = -1L
    private var lastAcceptedNanos = Long.MIN_VALUE
    private var gapStartSeq = -1
    private var gapDeadlineNanos = Long.MIN_VALUE

    init {
        require(maximumTrackedPackets > 0)
        require(holdNanos > 0)
        require(maximumConcealmentPackets > 0)
    }

    @Synchronized
    fun offer(seqValue: Int, rtpTimestamp: Long, payload: ByteArray): ReorderOutput {
        val seq = seqValue and 0xFFFF
        val timestamp = rtpTimestamp and 0xFFFF_FFFFL
        val now = clockNanos()
        var resynchronized = false
        if (nextSeq < 0) {
            nextSeq = seq
            maxSeq = seq
            latestTimestamp = timestamp
        } else {
            val distance = sequenceDistance(seq, nextSeq)
            val timestampAdvanced = latestTimestamp < 0 || timestampDistance(timestamp, latestTimestamp) > 0
            val idleReset = lastAcceptedNanos != Long.MIN_VALUE &&
                now - lastAcceptedNanos >= DISCONTINUITY_IDLE_NANOS
            val backwardReset = distance < 0 && (timestampAdvanced || idleReset)
            if (distance < 0 && !backwardReset) return finish(duplicateOrLate = true)
            if (distance > maximumTrackedPackets || backwardReset) {
                // A sender restart/discontinuity is not thousands of missing packets. Discard the
                // stale island and anchor at the newest packet in constant time.
                packets.clear()
                nextSeq = seq
                maxSeq = seq
                latestTimestamp = timestamp
                clearGapDeadline()
                resynchronized = true
            }
        }
        if (packets.putIfAbsent(seq, payload) != null) return finish(duplicateOrLate = true)
        if (sequenceDistance(seq, maxSeq) > 0) maxSeq = seq
        if (latestTimestamp < 0 || timestampDistance(timestamp, latestTimestamp) > 0) {
            latestTimestamp = timestamp
        }
        lastAcceptedNanos = now

        val ready = mutableListOf<ReorderedPacket>()
        drainContiguous(ready)
        var expiredGap = false
        refreshGapDeadline()
        if (gapStartSeq >= 0 && now >= gapDeadlineNanos) {
            expireBufferedGaps(ready)
            expiredGap = true
        }
        return finish(
            ready = ready,
            expiredGap = expiredGap,
            resynchronized = resynchronized
        )
    }

    @Synchronized
    fun expireGapIfDue(): ReorderOutput {
        refreshGapDeadline()
        if (gapStartSeq < 0 || clockNanos() < gapDeadlineNanos) return finish()
        val ready = mutableListOf<ReorderedPacket>()
        expireBufferedGaps(ready)
        return finish(ready, expiredGap = true)
    }

    @Synchronized fun currentGapRange(): ResendRange? = currentGap()
    @Synchronized fun hasGap(): Boolean = currentGap() != null
    @Synchronized fun nextExpectedSeq(): Int = nextSeq

    @Synchronized
    fun clear() {
        packets.clear()
        nextSeq = -1
        maxSeq = -1
        latestTimestamp = -1L
        lastAcceptedNanos = Long.MIN_VALUE
        clearGapDeadline()
    }

    private fun finish(
        ready: List<ReorderedPacket> = emptyList(),
        duplicateOrLate: Boolean = false,
        expiredGap: Boolean = false,
        resynchronized: Boolean = false
    ): ReorderOutput {
        refreshGapDeadline()
        return ReorderOutput(
            playoutFrames = ready,
            gap = currentGap(),
            nextExpectedSeq = nextSeq,
            duplicateOrLate = duplicateOrLate,
            expiredGap = expiredGap,
            resynchronized = resynchronized
        )
    }

    private fun drainContiguous(ready: MutableList<ReorderedPacket>) {
        while (true) {
            val packet = packets.remove(nextSeq) ?: break
            ready += ReorderedPacket.Encoded(packet)
            nextSeq = (nextSeq + 1) and 0xFFFF
        }
    }

    private fun expireBufferedGaps(ready: MutableList<ReorderedPacket>) {
        var concealmentRemaining = maximumConcealmentPackets
        var skipped = 0
        // Drain every already-buffered island in one bounded pass. Sparse loss therefore gets one
        // wall-clock hold, not another full delay for each hole.
        while (skipped <= maximumTrackedPackets) {
            val gap = currentGap() ?: break
            val concealed = gap.count.coerceAtMost(concealmentRemaining)
            repeat(concealed) { ready += ReorderedPacket.Concealment }
            concealmentRemaining -= concealed
            nextSeq = (nextSeq + gap.count) and 0xFFFF
            skipped += gap.count
            drainContiguous(ready)
        }
        clearGapDeadline()
    }

    private fun currentGap(): ResendRange? =
        AudioStreamServer.firstMissingRange(nextSeq, maxSeq) { packets.containsKey(it) }

    private fun refreshGapDeadline() {
        val gap = currentGap()
        if (gap == null) {
            clearGapDeadline()
        } else if (gapStartSeq < 0) {
            gapStartSeq = gap.startSeq
            gapDeadlineNanos = clockNanos() + holdNanos
        } else {
            // Preserve the first deadline while draining one already-buffered island. A late fill
            // may advance the hole, but it must not buy the same backlog another full hold window.
            gapStartSeq = gap.startSeq
        }
    }

    private fun clearGapDeadline() {
        gapStartSeq = -1
        gapDeadlineNanos = Long.MIN_VALUE
    }

    private fun sequenceDistance(a: Int, b: Int): Int =
        (((a - b) and 0xFFFF) xor 0x8000) - 0x8000

    private fun timestampDistance(a: Long, b: Long): Long =
        (((a - b) and 0xFFFF_FFFFL) xor 0x8000_0000L) - 0x8000_0000L

    private companion object {
        const val DEFAULT_MAXIMUM_CONCEALMENT_PACKETS = 4
        const val DISCONTINUITY_IDLE_NANOS = 500_000_000L
    }
}

/**
 * AudioStreamServer — receives and plays the AirPlay mirroring/realtime audio stream (type 96).
 *
 * macOS sends AES-128-CBC-encrypted AAC-ELD audio as RTP/UDP. We decrypt each packet (whole
 * 16-byte blocks; the trailing partial block is cleartext — the RAOP scheme), decode AAC-ELD via
 * MediaCodec, and play the PCM through AudioTrack.
 *
 * Architecture — the receiver and the player run on SEPARATE threads, decoupled by a bounded
 * queue (same pattern as [MirrorStreamServer] for video):
 *
 *   • Receive thread: socket.receive → dedup by RTP sequence → enqueue. Never blocks on playback,
 *     so the UDP socket is always drained promptly. (A blocking AudioTrack.write on the receive
 *     thread stalls the socket drain, which destabilises the whole mirror session.)
 *   • Playback thread: dequeue → decrypt → decode → AudioTrack.write(BLOCKING). Blocking here only
 *     paces playback to the audio clock and drops no PCM; it cannot stall the network.
 *
 * Reference: RPiPlay lib/raop_rtp.c + lib/raop_buffer.c (audio key = SHA-512(aesKey‖ecdh)[:16],
 * IV = SETUP eiv, AES-128-CBC per packet).
 */
class AudioStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    aesIv: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
    private val codecType: Int = CT_AAC_ELD,   // SETUP ct: 8 = AAC-ELD (mirror), 4 = AAC-LC (audio-only)
    private val framesPerPacket: Int = DEFAULT_ALAC_FRAMES,   // SETUP spf — ALAC frameLength (352)
    remoteAddress: InetAddress? = null,
    senderControlPort: Int = 0,
    private val latencyMinSamples: Int = 0,
    private val latencyMaxSamples: Int = 0,
    private val onFatalError: (Throwable) -> Unit = {}
) {
    init {
        require(sampleRate in 8_000..192_000)
        require(channels in 1..2)
        require(codecType in setOf(CT_ALAC, CT_AAC_LC, CT_AAC_ELD))
        require(codecType == CT_ALAC || sampleRate in SUPPORTED_AAC_SAMPLE_RATES) {
            "unsupported AAC sample rate: $sampleRate"
        }
        require(framesPerPacket in 64..4_096)
        require(latencyMinSamples >= 0 && latencyMaxSamples >= 0)
    }

    private val key = SecretKeySpec(MirrorCrypto.audioKey(aesKey, ecdhSecret), "AES")
    private val iv = IvParameterSpec(aesIv.copyOf(16))

    // Playback gain (0..1), set from the sender's AirPlay volume. Applied to the AudioTrack and
    // re-applied if the track is recreated. Starts at full.
    @Volatile private var volumeGain = 1f

    // Reused across packets: decryptPacket runs only on the playback thread, so one Cipher
    // instance is safe and avoids a Cipher.getInstance allocation on every packet (~92/s).
    private val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")

    // Bind to the IPv6 wildcard (dual-stack) — macOS sends the audio RTP over the session's
    // IPv6 link-local address; a default DatagramSocket binds IPv4-only and never receives it.
    private val sockets = ipv6SocketPair()
    private val socket = sockets.first
    private val controlSocket = sockets.second   // realtime-audio control channel (drained)

    @Volatile private var running = false
    private val failureNotified = AtomicBoolean(false)
    private val playbackStopped = CountDownLatch(1)
    @Volatile private var playbackJob: Job? = null
    @Volatile private var playbackThread: Thread? = null
    private var codec: MediaCodec? = null
    private var alac: AlacDecoder? = null      // software ALAC decoder (ct=2 system-audio path)
    private var audioTrack: AudioTrack? = null
    private var firstPcm = true
    private val lastPcmSamples = ShortArray(channels)
    private var fadeInAfterConcealment = false

    // Decoded-audio jitter buffer: raw (post-dedup) RTP payloads handed from the receive thread to
    // the playback thread. Bounded so a stalled player can't grow latency unboundedly — if it fills
    // we drop the oldest frame (a brief glitch is better than ever-growing audio lag).
    private val packetFrames = when (codecType) { CT_AAC_ELD -> 480; CT_AAC_LC -> 1024; else -> framesPerPacket }
    // Mirroring retains the original tight budget. Music-only storage honors the sender's bounded
    // latencyMax window so a recovered Wi-Fi burst is not discarded after the radio catches up.
    // Capacity is only a ceiling: [MusicPlayoutPolicy] still controls the small startup prime.
    private val frameQueue = ArrayBlockingQueue<QueuedAudioFrame>(
        queueCapacityPackets(codecType, sampleRate, packetFrames, latencyMaxSamples)
    )
    private var decodedSamples = 0L

    // ─── Reorder buffer + packet-loss retransmit ─────────────────────────────
    // macOS's `redundantAudio` (each packet sent 2–3×) covers most loss, but a burst that drops
    // all copies leaves a gap. We hold packets in a small seq-keyed reorder buffer and, on a gap,
    // ask the sender to resend the missing range (RAOP control type 0x55) — the resent packet comes
    // back on the control socket (type 0x56) and fills the hole. Common case (in-order) releases
    // immediately with ZERO added latency; only an actual gap briefly holds, bounded by
    // the negotiated wall-clock budget, so A/V sync is preserved. Touched by both the data-receive
    // and control threads (resend replies), so duplicate tracking stays under [reorderLock].
    private val reorderLock = Any()
    private val sendLock = Any()                       // serialises control-socket resend sends
    private val reorderBuffer = RtpReorderBuffer(
        maximumTrackedPackets = MAX_RESEND_RANGE,
        holdNanos = reorderBudgetMillis(codecType) * 1_000_000L,
        maximumConcealmentPackets = concealmentBudgetPackets(codecType, sampleRate, packetFrames)
    )
    private val postFlushIngress = PostFlushIngressGate()
    private val resendRetry = ResendRetryPolicy()
    private val resendChannel = RaopResendChannel(
        remoteAddress?.takeIf { senderControlPort in 1..65535 }
            ?.let { InetSocketAddress(it, senderControlPort) },
        expectedPeer = remoteAddress
    ) { bytes, destination ->
        controlSocket.send(DatagramPacket(bytes, bytes.size, destination))
    }
    @Volatile private var dupCount = 0
    @Volatile private var qDropCount = 0
    @Volatile private var resendFillCount = 0
    private val flushRequested = AtomicBoolean(false)
    private val flushGeneration = AtomicLong(0L)
    private var playoutPolicy: MusicPlayoutPolicy? = null
    private val decoderHealth = DecoderHealthPolicy()
    private var acceptingPackets = true

    /** UDP port macOS sends the audio RTP stream to (returned in the SETUP response). */
    val dataPort: Int get() = socket.localPort

    /** UDP control port (returned in the SETUP response; macOS won't send audio without it). */
    val controlPort: Int get() = controlSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        StreamStats.audioActive = true
        playbackJob = scope.launch(Dispatchers.IO) { runPlayback() }.also { job ->
            job.invokeOnCompletion { playbackStopped.countDown() }
        } // decode + play (may block on AudioTrack)
        scope.launch(Dispatchers.IO) { runReceive() }    // drain socket fast (never blocks on audio)
        scope.launch(Dispatchers.IO) { runControl() }    // capture sender addr + handle resend replies
    }

    /**
     * Control channel: the sender posts periodic timing/sync packets here (RTP type 0x54, marker →
     * 0xD4), and — after we ask — resent audio packets (RTP type 0x56 → 0xD6). The negotiated
     * sender control endpoint is preferred; an observed packet source is only a legacy fallback.
     */
    private fun runControl() {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var ctrlCount = 0
        try {
            while (running) {
                pkt.length = buf.size     // reset capacity before each receive (see runReceive)
                controlSocket.receive(pkt)
                if (pkt.length < 2) continue
                val learnedFallback = resendChannel.learnFallback(pkt.socketAddress)
                if (learnedFallback) {
                    // A legacy sender may omit controlPort and reveal it only with its first
                    // control datagram. Do not spend the gap's retry budget before that happens.
                    retryPendingGap()?.let(::requestResend)
                }
                if (ctrlCount < 6) {
                    Logger.i("Audio CTRL[$ctrlCount] ${pkt.length}B: ${hex(pkt.data, minOf(20, pkt.length))}")
                    ctrlCount++
                }
                // RTP payload type is bits 0–6 of byte 1 (byte 1 = marker<<7 | type).
                val payloadType = pkt.data[1].toInt() and 0x7F
                if (payloadType == RTP_TYPE_RESEND_REPLY && pkt.length > RESEND_REPLY_HEADER + RTP_HEADER) {
                    resendChannel.confirmReplySource(pkt.socketAddress)
                    // bytes [4..] are the original audio RTP packet — feed it through the normal path.
                    resendFillCount++
                    handleRtpPacket(pkt.data, RESEND_REPLY_HEADER, pkt.length - RESEND_REPLY_HEADER)
                }
            }
        } catch (e: Exception) {
            if (running) failSession(IllegalStateException("AirPlay audio control channel failed", e))
        }
    }

    /** Stops transport and waits briefly for the playback owner to release its AudioTrack. */
    fun stop(): Boolean {
        running = false
        StreamStats.audioActive = false
        runCatching { socket.close() }
        runCatching { controlSocket.close() }
        frameQueue.clear()
        synchronized(reorderLock) { reorderBuffer.clear() }
        // NOTE: codec + audioTrack are deliberately NOT released here. They are owned and released
        // exclusively by the playback thread (see runPlayback's finally). Releasing MediaCodec from
        // this thread races decodeFrame on the playback thread and crashes the whole process with a
        // native SIGABRT ("pthread_mutex_destroy called on a destroyed mutex" inside libstagefright).
        // Flipping `running` makes the playback loop exit within one poll timeout and clean up safely.
        val released = playbackJob == null || Thread.currentThread() === playbackThread ||
            playbackStopped.await(PLAYBACK_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!released) {
            Logger.w("Audio playback owner did not release within ${PLAYBACK_STOP_TIMEOUT_MS}ms")
        }
        return released
    }

    /**
     * Discards audio queued before an AirPlay FLUSH/PAUSE while retaining ports, keys, and threads.
     * Codec and AudioTrack flushing is requested here but performed by their playback-owner thread.
     */
    fun flush(minimumSequence: Int? = null, minimumTimestamp: Long? = null) {
        synchronized(reorderLock) {
            resetBufferedAudio(minimumSequence, minimumTimestamp)
        }
        StreamStats.audioQueue = 0
    }

    /** PAUSE keeps the authenticated route but rejects every UDP packet until RECORD resumes it. */
    fun pause() {
        synchronized(reorderLock) {
            acceptingPackets = false
            resetBufferedAudio(null, null)
        }
        StreamStats.audioQueue = 0
    }

    /** RECORD resumes delivery at the sender's RTP-Info boundary when one is provided. */
    fun resume(minimumSequence: Int? = null, minimumTimestamp: Long? = null) {
        synchronized(reorderLock) {
            if (minimumSequence == null && minimumTimestamp == null) {
                postFlushIngress.acceptNextEpoch()
            } else {
                postFlushIngress.arm(minimumSequence, minimumTimestamp)
            }
            acceptingPackets = true
        }
    }

    private fun resetBufferedAudio(minimumSequence: Int?, minimumTimestamp: Long?) {
        frameQueue.clear()
        reorderBuffer.clear()
        resendRetry.clear()
        postFlushIngress.arm(minimumSequence, minimumTimestamp)
        decoderHealth.reset()
        flushGeneration.incrementAndGet()
        flushRequested.set(true)
    }

    /** Receive thread: pull RTP packets off the data socket and feed them to the reorder buffer. */
    private fun runReceive() {
        try {
            Logger.i("AudioStreamServer listening on UDP $dataPort (ct=$codecType ${sampleRate}Hz x$channels)")
            // Stay blocking while idle. A short timeout is armed only while a real gap has
            // retries remaining, avoiding a permanent stream of timeout exceptions on pause.
            socket.soTimeout = 0
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            var rtpCount = 0
            var recv = 0
            while (running) {
                packet.length = buf.size      // reset capacity — receive() shrinks length to the last datagram
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    expirePendingGap()
                    val retry = retryPendingGap()
                    retry?.let(::requestResend)
                    socket.soTimeout = if (hasPendingGap()) RESEND_RETRY_POLL_MS else 0
                    continue
                }
                recv++
                if (rtpCount < 6) {
                    Logger.i("Audio RTP[$rtpCount] ${packet.length}B hdr: ${hex(packet.data, minOf(20, packet.length))}")
                    rtpCount++
                }
                handleRtpPacket(packet.data, 0, packet.length)
                socket.soTimeout = if (hasPendingGap()) RESEND_RETRY_POLL_MS else 0
                StreamStats.audioQueue = frameQueue.size
                if (recv % 500 == 0) {
                    StreamStats.audioDupPct = dupCount * 100 / (recv + dupCount)
                    Logger.i("Audio stats: recv=$recv dup=$dupCount (${StreamStats.audioDupPct}% dup) " +
                        "qDrop=$qDropCount resendOK=${resendChannel.successfulRequests} " +
                        "resendFail=${resendChannel.failedRequests} resendFill=$resendFillCount queue=${frameQueue.size}")
                }
            }
        } catch (e: Exception) {
            if (running) failSession(IllegalStateException("AirPlay audio data channel failed", e))
        }
    }

    /**
     * Parses one RTP audio packet (from the data socket or a resend reply) and routes it through the
     * reorder buffer. [src] may be a reused receive buffer, so the payload is copied out before any
     * cross-thread handoff. Thread-safe: the reorder buffer + dedup are accessed under [reorderLock].
     */
    private fun handleRtpPacket(src: ByteArray, offset: Int, length: Int) {
        val range = audioRtpPayloadRange(src, offset, length) ?: return
        StreamStats.markMediaPacket()
        val seq = ((src[offset + 2].toInt() and 0xFF) shl 8) or (src[offset + 3].toInt() and 0xFF)
        val timestamp = ((src[offset + 4].toLong() and 0xFF) shl 24) or
            ((src[offset + 5].toLong() and 0xFF) shl 16) or
            ((src[offset + 6].toLong() and 0xFF) shl 8) or
            (src[offset + 7].toLong() and 0xFF)
        // RAOP RTP: 12-byte header, then AES-128-CBC-encrypted audio payload (copied out of src).
        val payload = src.copyOfRange(range.first, range.last + 1)
        val resend = synchronized(reorderLock) {
            enqueueInOrder(seq, timestamp, payload)
        }
        // Send the resend request OUTSIDE the reorder lock — never hold it across socket I/O.
        resend?.let(::requestResend)
    }

    /**
     * Inserts [seq]/[payload] into the reorder buffer and releases all now-contiguous packets to the
     * player in order. Returns a missing range to resend (or null). Under [reorderLock].
     */
    private fun enqueueInOrder(seq: Int, timestamp: Long, payload: ByteArray): ResendRange? {
        if (!acceptingPackets) return null
        if (!postFlushIngress.accept(seq, timestamp)) {
            Logger.d("Discarding pre-FLUSH RTP packet seq=$seq timestamp=$timestamp")
            return null
        }
        val output = reorderBuffer.offer(seq, timestamp, payload)
        if (output.resynchronized) postFlushIngress.reanchor(seq, timestamp)
        consumeReorderOutput(output)
        return currentGapRequest()
    }

    private fun consumeReorderOutput(output: ReorderOutput) {
        if (output.duplicateOrLate) dupCount++
        if (output.resynchronized) {
            Logger.w("Audio RTP sequence discontinuity; resynchronized")
            frameQueue.clear()
            flushGeneration.incrementAndGet()
            flushRequested.set(true)
        }
        if (output.expiredGap) Logger.w("Audio RTP gap expired within bounded playout window")
        val generation = flushGeneration.get()
        output.playoutFrames.forEach { frame ->
            when (frame) {
                is ReorderedPacket.Encoded -> offerFrame(QueuedAudioFrame.Encoded(frame.bytes, generation))
                ReorderedPacket.Concealment -> offerFrame(QueuedAudioFrame.Silence(generation))
            }
        }
        resendRetry.resolved(output.nextExpectedSeq)
    }

    private fun offerFrame(frame: QueuedAudioFrame) {
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()
            frameQueue.offer(frame)
            qDropCount++
        }
    }

    private fun retryPendingGap(): ResendRange? = synchronized(reorderLock) {
        currentGapRequest()
    }

    private fun hasPendingGap(): Boolean = synchronized(reorderLock) {
        reorderBuffer.hasGap()
    }

    private fun expirePendingGap() = synchronized(reorderLock) {
        consumeReorderOutput(reorderBuffer.expireGapIfDue())
    }

    private fun currentGapRequest(): ResendRange? {
        if (!resendChannel.isReady()) return null
        val missing = reorderBuffer.currentGapRange() ?: return null
        return resendRetry.request(missing.startSeq, missing.count)
    }

    /** Sends one RAOP resend request outside the reorder lock; [sendLock] serializes both sockets. */
    private fun requestResend(range: ResendRange) {
        synchronized(sendLock) {
            if (!resendChannel.request(range)) {
                Logger.w("Audio resend request could not be delivered")
            }
        }
    }

    /**
     * Playback thread: decrypt + decode queued frames and write PCM to AudioTrack. This thread is
     * the SOLE owner of [codec] and [audioTrack] — it creates them here and releases them in the
     * finally block, so no other thread ever touches the codec concurrently (see [stop]).
     */
    private fun runPlayback() {
        playbackThread = Thread.currentThread()
        var fatalError: Throwable? = null
        try {
            if (!running) return
            initDecoder()
            initAudioTrack()
            while (running) {
                drainPendingFlush()
                val frame = frameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                drainPendingFlush()
                if (frame.generation != flushGeneration.get()) continue
                try {
                    when (frame) {
                        is QueuedAudioFrame.Encoded -> {
                            val decrypted = decryptPacket(frame.bytes)
                            val producedPcm = if (alac != null) {
                                playAlacFrame(decrypted, frame.generation)
                                true
                            } else {
                                decodeFrame(decrypted, frame.generation)
                            }
                            if (producedPcm) {
                                decoderHealth.onPcmProduced()
                            } else if (decoderHealth.onFrameWithoutPcm()) {
                                throw AudioSinkException("Audio decoder produced no PCM for 32 frames")
                            }
                        }
                        is QueuedAudioFrame.Silence -> playConcealment(frame.generation)
                    }
                } catch (e: Exception) {
                    if (e is AudioSinkException) throw e
                    if (decoderHealth.onDecodeFailure()) {
                        throw AudioSinkException("Audio decoder failed repeatedly", e)
                    }
                    if (running) Logger.w("Audio: damaged frame concealed (${e.message})")
                    playConcealment(frame.generation)
                }
            }
        } catch (e: Exception) {
            if (running) {
                fatalError = e
                Logger.e("Audio playback failed; ending the stale sender session", e)
            }
        } finally {
            running = false
            StreamStats.audioActive = false
            runCatching { socket.close() }
            runCatching { controlSocket.close() }
            // Release on the same thread that used the codec — never cross-thread (avoids SIGABRT).
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { alac?.close() }
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            codec = null
            alac = null
            audioTrack = null
            playbackThread = null
            Logger.i("AudioStreamServer stopped")
            fatalError?.let(::failSession)
        }
    }

    private fun failSession(error: Throwable) {
        if (!failureNotified.compareAndSet(false, true)) return
        running = false
        StreamStats.audioActive = false
        runCatching { socket.close() }
        runCatching { controlSocket.close() }
        Logger.e("Realtime audio transport is unusable; closing sender session", error)
        runCatching { onFatalError(error) }
            .onFailure { Logger.e("Unable to notify receiver of fatal audio failure", it) }
    }

    /** Decode one decrypted ALAC frame to PCM and write it to AudioTrack (blocking, paces playback). */
    private fun playAlacFrame(frame: ByteArray, generation: Long) {
        val pcm = alac?.decode(frame)
            ?: throw IllegalStateException("ALAC decoder returned no PCM")
        if (firstPcm) { Logger.i("Audio: first decoded ALAC PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
        writePcm(pcm, generation)
    }

    private fun playConcealment(generation: Long) {
        val concealed = buildFadedPcmSilence(lastPcmSamples, packetFrames, sampleRate)
        lastPcmSamples.fill(0)
        fadeInAfterConcealment = true
        writePcm(concealed, generation, concealment = true)
    }

    private fun writePcm(pcm: ByteArray, generation: Long, concealment: Boolean = false) {
        if (generation != flushGeneration.get()) return
        val track = audioTrack ?: return
        val policy = playoutPolicy
        if (policy != null && Build.VERSION.SDK_INT >= 24 && policy.onUnderrunCount(track.underrunCount)) {
            try {
                track.pause()
                track.flush()
            } catch (e: Exception) {
                throw AudioSinkException("AudioTrack rebuffer reset failed", e)
            }
            Logger.w("AudioTrack underrun detected; re-priming ${policy.targetPrimeBytes} bytes")
        }
        if (!concealment) {
            if (fadeInAfterConcealment) {
                applyPcmFadeInInPlace(pcm, channels, sampleRate)
                fadeInAfterConcealment = false
            }
            val frameBytes = channels * 2
            if (pcm.size >= frameBytes) {
                val base = pcm.size - frameBytes
                for (channel in 0 until channels) {
                    val offset = base + channel * 2
                    val raw = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
                    lastPcmSamples[channel] = raw.toShort()
                }
            }
        }
        // Apply sender volume to PCM so TCL vendor output cannot ignore AudioTrack's per-track gain.
        applyPcm16LeGainInPlace(pcm, volumeGain)
        var offset = 0
        while (running && generation == flushGeneration.get() && offset < pcm.size) {
            val priming = policy != null && !policy.started
            val writeMode = if (priming) AudioTrack.WRITE_NON_BLOCKING else AudioTrack.WRITE_BLOCKING
            val written = guardedAudioWrite {
                track.write(pcm, offset, pcm.size - offset, writeMode)
            }
            if (written == 0 && priming && policy?.startWithPrimedData() == true) {
                playTrack(track)
                Logger.i("AudioTrack reached platform capacity while priming; playback started")
                continue
            }
            offset += requireAudioWriteResult(written)
            if (policy?.onBytesWritten(written) == true) {
                playTrack(track)
                Logger.i("AudioTrack primed; music playback started")
            }
        }
    }

    private fun playTrack(track: AudioTrack) {
        try {
            track.play()
        } catch (e: Exception) {
            throw AudioSinkException("AudioTrack could not start", e)
        }
    }

    /** AES-128-CBC decrypt the whole-block portion; the trailing < 16 bytes stay cleartext. */
    private fun decryptPacket(payload: ByteArray): ByteArray {
        val encryptedLen = (payload.size / 16) * 16
        if (encryptedLen == 0) return payload
        cbcCipher.init(Cipher.DECRYPT_MODE, key, iv)   // fresh IV per packet (RAOP)
        val out = payload.copyOf()
        cbcCipher.doFinal(payload, 0, encryptedLen, out, 0)
        return out
    }

    private fun decodeFrame(aac: ByteArray, generation: Long): Boolean {
        if (generation != flushGeneration.get()) return false
        val mc = codec ?: return false
        var producedPcm = false
        var inIdx = mc.dequeueInputBuffer(0)
        if (inIdx < 0) {
            producedPcm = drainDecodedOutput(mc, generation)
            inIdx = mc.dequeueInputBuffer(10_000)
        }
        if (inIdx >= 0) {
            mc.getInputBuffer(inIdx)!!.apply { clear(); put(aac) }
            mc.queueInputBuffer(inIdx, 0, aac.size, decodedSamples * 1_000_000 / sampleRate, 0)
            decodedSamples += packetFrames
        }
        return drainDecodedOutput(mc, generation) || producedPcm
    }

    private fun drainDecodedOutput(mc: MediaCodec, generation: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        var producedPcm = false
        var outIdx = mc.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            val outBuf: ByteBuffer = mc.getOutputBuffer(outIdx)!!
            val pcm = ByteArray(info.size)
            outBuf.position(info.offset); outBuf.get(pcm)
            try {
                if (pcm.isNotEmpty()) {
                    if (firstPcm) { Logger.i("Audio: first decoded PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
                    // Blocking write paces playback to the audio clock and drops no PCM. Safe here
                    // because this is the dedicated playback thread, not the UDP receive thread.
                    writePcm(pcm, generation)
                    producedPcm = true
                }
            } finally {
                mc.releaseOutputBuffer(outIdx, false)
            }
            outIdx = mc.dequeueOutputBuffer(info, 0)
        }
        return producedPcm
    }

    private fun initDecoder() {
        if (codecType == CT_ALAC) {
            // macOS sends ALAC (lossless) for system-audio AirPlay regardless of our advertised
            // formats, and this TV has no hardware ALAC codec — so we decode in software via the
            // bundled Apple ALAC decoder (libalac.so). frameLength comes from the SETUP spf.
            alac = AlacDecoder(sampleRate, channels, framesPerPacket)
            Logger.i("Audio decoder: ALAC ${sampleRate}Hz x$channels spf=$framesPerPacket (ct=2)")
            return
        }
        // ct=8 AAC-ELD (mirroring, spf 480) vs ct=4 AAC-LC (audio-only / Apple Music, spf 1024).
        val isAacLc = codecType == CT_AAC_LC
        val profile = if (isAacLc) MediaCodecInfo.CodecProfileLevel.AACObjectLC
                      else MediaCodecInfo.CodecProfileLevel.AACObjectELD
        val asc = if (isAacLc) buildAacLcAsc(sampleRate, channels) else buildAacEldAsc(sampleRate, channels)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        Logger.i("Audio decoder: ${if (isAacLc) "AAC-LC" else "AAC-ELD"} ${sampleRate}Hz x$channels (ct=$codecType)")
    }

    /** Sets playback volume from the sender's AirPlay volume (−30 dB … 0 dB, or ≤ −144 = mute). */
    fun setVolume(airplayVolume: Float) {
        if (!airplayVolume.isFinite()) return
        volumeGain = airplayVolumeGain(airplayVolume)
    }

    /** Playback-thread-only portion of FLUSH; avoids cross-thread MediaCodec/AudioTrack races. */
    private fun drainPendingFlush() {
        if (!flushRequested.getAndSet(false)) return
        decodedSamples = 0L
        lastPcmSamples.fill(0)
        fadeInAfterConcealment = false
        try {
            // Synchronous ByteBuffer-mode codecs resume on the next dequeue after flush; calling
            // start() again is for asynchronous callback mode and breaks some vendor codecs.
            codec?.flush()
        } catch (e: Exception) {
            throw IllegalStateException("Audio decoder flush failed", e)
        }
        try {
            audioTrack?.let {
                val musicPolicy = playoutPolicy
                val resumeImmediately = musicPolicy == null && it.playState == AudioTrack.PLAYSTATE_PLAYING
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()
                musicPolicy?.reset()
                if (resumeImmediately && running) playTrack(it)
            }
        } catch (e: AudioSinkException) {
            throw e
        } catch (e: Exception) {
            throw AudioSinkException("AudioTrack flush failed", e)
        }
        Logger.d("AudioStreamServer flush complete")
    }

    private fun initAudioTrack() {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuf > 0) { "AudioTrack rejected negotiated audio format: $minBuf" }
        val bytesPerSec = sampleRate * channels * 2
        val musicOnly = codecType != CT_AAC_ELD
        val trackBufferBytes = if (musicOnly) minBuf * 2 else minBuf
        val packetPcmBytes = packetFrames * channels * 2
        val maximumPrimeBytes = (trackBufferBytes - packetPcmBytes).coerceAtLeast(minBuf)
        val initialPrimeBytes = initialMusicPrimeBytes(
            minBufferBytes = minBuf,
            maximumPrimeBytes = maximumPrimeBytes,
            latencyMinSamples = latencyMinSamples,
            channels = channels
        )
        val senderMinimumMs = if (latencyMinSamples > 0) latencyMinSamples * 1_000L / sampleRate else 0L
        playoutPolicy = if (musicOnly) {
            MusicPlayoutPolicy(
                initialPrimeBytes = initialPrimeBytes,
                maximumPrimeBytes = maximumPrimeBytes,
                rebufferStepBytes = packetPcmBytes * 8
            )
        } else {
            null
        }
        Logger.i("AudioTrack: minBuf=${minBuf}B (~${minBuf * 1000 / bytesPerSec}ms), " +
            "buffer=${trackBufferBytes}B (~${trackBufferBytes * 1000 / bytesPerSec}ms capacity), " +
            "prime=${if (musicOnly) initialPrimeBytes else 0}B senderMin=${senderMinimumMs}ms")
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (musicOnly) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            // Minimum buffer for LOW LATENCY so audio lines up with the (immediately-rendered)
            // video. The upstream dedup jitter queue absorbs network jitter, so AudioTrack itself
            // only needs the floor. (If this underruns/crackles on load, raise toward minBuf*2.)
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            .build()
            // Sender gain is applied to PCM samples, leaving the platform track at unity.
            .also {
                it.setVolume(1f)
                if (musicOnly) {
                    if (Build.VERSION.SDK_INT >= 24) playoutPolicy?.setUnderrunBaseline(it.underrunCount)
                } else {
                    playTrack(it)
                }
            }
    }

    companion object {
        const val CT_ALAC = 2      // SETUP ct for ALAC (system-audio AirPlay; decoded in software)
        const val CT_AAC_LC = 4    // SETUP ct for AAC-LC (audio-only / Apple Music)
        const val CT_AAC_ELD = 8   // SETUP ct for AAC-ELD (screen-mirroring realtime audio)

        // ALAC frameLength macOS uses for realtime system-audio AirPlay (SETUP spf). Used to size
        // the ALAC magic cookie + decode buffers when the sender omits spf.
        private const val DEFAULT_ALAC_FRAMES = 352

        private const val RTP_HEADER = 12

        // RAOP control-channel RTP payload types for packet-loss recovery.
        private const val RTP_TYPE_RESEND_REPLY = 0x56     // sender → us: a resent audio packet
        private const val RESEND_REPLY_HEADER = 4          // 4-byte resend header before the embedded RTP
        private const val RESEND_RETRY_POLL_MS = 30

        // Don't ask for an absurd resend range (a huge gap = a real stall, not a few lost packets).
        private const val MAX_RESEND_RANGE = 128

        /** Packet-count budget scales with negotiated codec duration, rather than adding ~1s. */
        internal fun packetBudget(
            sampleRate: Int,
            frames: Int,
            milliseconds: Int,
            maximumPackets: Int = 32
        ): Int {
            require(sampleRate > 0 && frames > 0 && milliseconds > 0)
            require(maximumPackets > 0)
            return (sampleRate.toLong() * milliseconds / (frames.toLong() * 1000)).toInt()
                .coerceIn(1, maximumPackets)
        }

        internal fun queueBudgetMillis(codecType: Int): Int =
            if (codecType == CT_AAC_ELD) 100 else MAX_MUSIC_QUEUE_MILLIS

        internal fun reorderBudgetMillis(codecType: Int): Int =
            if (codecType == CT_AAC_ELD) 40 else MUSIC_REORDER_MILLIS

        /**
         * Preserve every packet interval that can expire inside the negotiated recovery window.
         * A smaller fixed concealment cap compresses the media timeline after a music burst,
         * making the recovered stream sound like it skips even though later packets are valid.
         */
        internal fun concealmentBudgetPackets(codecType: Int, sampleRate: Int, frames: Int): Int =
            packetBudget(
                sampleRate,
                frames,
                reorderBudgetMillis(codecType),
                maximumPackets = MAX_RESEND_RANGE
            )

        /**
         * Hard storage ceiling for packet bursts. Music capacity does not prebuffer this amount;
         * normal start latency is controlled separately by [MusicPlayoutPolicy]. When the sender
         * supplies a very large latencyMax, retain at most the AirPlay music ceiling. Unlike a
         * forced prebuffer, this storage adds no normal startup delay; it is used only when a weak
         * radio delivers a catch-up burst after a gap.
         */
        internal fun queueCapacityPackets(
            codecType: Int,
            sampleRate: Int,
            frames: Int,
            latencyMaxSamples: Int
        ): Int {
            if (codecType == CT_AAC_ELD) {
                return packetBudget(sampleRate, frames, queueBudgetMillis(codecType))
            }
            val negotiated = if (latencyMaxSamples > 0) {
                (latencyMaxSamples.toLong() + frames - 1L) / frames
            } else {
                sampleRate.toLong() * MAX_MUSIC_QUEUE_MILLIS / (frames.toLong() * 1_000L)
            }
            val maximumPackets = ((sampleRate.toLong() * MAX_MUSIC_QUEUE_MILLIS +
                frames.toLong() * 1_000L - 1L) / (frames.toLong() * 1_000L))
                .coerceAtMost(MAX_MUSIC_QUEUE_PACKETS.toLong()).toInt()
            val minimumPackets = minOf(MIN_MUSIC_QUEUE_PACKETS, maximumPackets)
            return negotiated.coerceIn(minimumPackets.toLong(), maximumPackets.toLong()).toInt()
        }

        internal const val MAX_MUSIC_QUEUE_MILLIS = 2_000
        internal const val MUSIC_REORDER_MILLIS = 75
        internal const val OUTPUT_CAPACITY_BUDGET_MILLIS = 288
        internal const val LOW_LATENCY_CONTRACT_MILLIS = 2_500
        internal const val REQUIRED_PIPELINE_HEADROOM_MILLIS = 25
        private const val MAX_MUSIC_QUEUE_PACKETS = 256
        private const val MIN_MUSIC_QUEUE_PACKETS = 4
        private const val PLAYBACK_STOP_TIMEOUT_MS = 500L
        internal val SUPPORTED_AAC_SAMPLE_RATES = setOf(
            8_000, 11_025, 12_000, 16_000, 22_050, 24_000,
            32_000, 44_100, 48_000, 64_000, 88_200, 96_000
        )

        /** First contiguous missing run only; never resend packets already present after the hole. */
        internal fun firstMissingRange(
            nextSeq: Int,
            maxSeq: Int,
            isPresent: (Int) -> Boolean
        ): ResendRange? {
            if (nextSeq < 0 || maxSeq < 0 || isPresent(nextSeq)) return null
            val span = (((maxSeq - nextSeq) and 0xFFFF) xor 0x8000) - 0x8000
            if (span !in 1..MAX_RESEND_RANGE) return null
            var count = 1
            while (count < span && !isPresent((nextSeq + count) and 0xFFFF)) count++
            return ResendRange(nextSeq, count)
        }

        /** Honor the sender's requested minimum without filling AudioTrack to a blocking deadlock. */
        internal fun initialMusicPrimeBytes(
            minBufferBytes: Int,
            maximumPrimeBytes: Int,
            latencyMinSamples: Int,
            channels: Int
        ): Int {
            require(minBufferBytes > 0 && maximumPrimeBytes >= minBufferBytes && channels > 0)
            val senderMinimumBytes = latencyMinSamples.coerceAtLeast(0).toLong() * channels * 2L
            return maxOf(minBufferBytes.toLong(), senderMinimumBytes)
                .coerceAtMost(maximumPrimeBytes.toLong())
                .toInt()
        }

        private fun hex(b: ByteArray, len: Int): String =
            (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }

        /** A UDP socket bound to the IPv6 wildcard (dual-stack), OS-assigned port. */
        private fun ipv6Socket(): DatagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), 0))
        }

        private fun ipv6SocketPair(): Pair<DatagramSocket, DatagramSocket> {
            val data = ipv6Socket()
            return try {
                data to ipv6Socket()
            } catch (e: Exception) {
                data.close()
                throw e
            }
        }

        @Suppress("unused")
        private val AUDIO_MANAGER_HINT = AudioManager.STREAM_MUSIC

        /**
         * Builds the AAC-ELD AudioSpecificConfig (csd-0) for the negotiated [sampleRate] and
         * [channels], instead of hardcoding 44.1 kHz/stereo. Layout: AOT escape(5)=31 + ext(6)=7
         * (AOT 39 = ELD), samplingFrequencyIndex(4), channelConfiguration(4), then the fixed
         * ELDSpecificConfig tail (frameLengthFlag=1 for 480 samples; resilience/SBR flags 0;
         * ELDEXT_TERM). For 44.1 kHz stereo this yields the canonical bytes F8 E8 50 00.
         */
        /** ISO 14496-3 sampling-frequency index for an AAC AudioSpecificConfig. */
        private fun freqIndexFor(sampleRate: Int): Int = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; 7350 -> 12
            else -> throw IllegalArgumentException("unsupported AAC sample rate: $sampleRate")
        }

        /**
         * Builds the AAC-LC AudioSpecificConfig (csd-0): AOT(5)=2 (LC), samplingFrequencyIndex(4),
         * channelConfiguration(4), GASpecificConfig flags(3)=0. For 44.1 kHz stereo → bytes 12 10.
         */
        fun buildAacLcAsc(sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = freqIndexFor(sampleRate)
            var bits = 0
            var n = 0
            fun put(value: Int, width: Int) { bits = (bits shl width) or (value and ((1 shl width) - 1)); n += width }
            put(2, 5); put(freqIndex, 4); put(channels, 4); put(0, 3)   // 16 bits total
            bits = bits shl (16 - n)
            return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
        }

        fun buildAacEldAsc(sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = freqIndexFor(sampleRate)
            var bits = 0L
            var n = 0
            fun put(value: Int, width: Int) {
                bits = (bits shl width) or (value.toLong() and ((1L shl width) - 1))
                n += width
            }
            put(31, 5); put(7, 6)                 // AOT escape → 39 (ELD)
            put(freqIndex, 4); put(channels, 4)
            put(1, 1); put(0, 4); put(0, 4)       // frameLengthFlag=1, resilience/SBR=0, ELDEXT_TERM=0
            bits = bits shl (32 - n)              // left-align into 4 bytes
            return byteArrayOf(
                (bits ushr 24).toByte(),
                (bits ushr 16).toByte(),
                (bits ushr 8).toByte(),
                bits.toByte()
            )
        }
    }
}
