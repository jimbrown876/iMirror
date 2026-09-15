package dev.imirror.receiver.airplay

import dev.imirror.receiver.airplay.handshake.AudioStreamServer
import dev.imirror.receiver.airplay.handshake.AudioSinkException
import dev.imirror.receiver.airplay.handshake.DecoderHealthPolicy
import dev.imirror.receiver.airplay.handshake.applyPcmFadeInInPlace
import dev.imirror.receiver.airplay.handshake.buildFadedPcmSilence
import dev.imirror.receiver.airplay.handshake.MusicPlayoutPolicy
import dev.imirror.receiver.airplay.handshake.PostFlushIngressGate
import dev.imirror.receiver.airplay.handshake.RaopResendChannel
import dev.imirror.receiver.airplay.handshake.ReorderedPacket
import dev.imirror.receiver.airplay.handshake.ResendRange
import dev.imirror.receiver.airplay.handshake.ResendRetryPolicy
import dev.imirror.receiver.airplay.handshake.RtpReorderBuffer
import dev.imirror.receiver.airplay.handshake.guardedAudioWrite
import dev.imirror.receiver.airplay.handshake.requireAudioWriteResult
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class AudioBufferingTest {
    private fun sampleAt(bytes: ByteArray, index: Int): Short {
        val offset = index * 2
        return ((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)).toShort()
    }

    @Test
    fun `partial nonblocking writes preserve every PCM sample on the next packet`() {
        val queue = PcmWriteQueue(32, 2)
        val heard = mutableListOf<Byte>()
        var calls = 0
        queue.enqueueAndDrain(byteArrayOf(1, 2, 3, 4, 5, 6)) { bytes, offset, _ ->
            if (calls++ == 0) { heard.addAll(bytes.copyOfRange(offset, offset + 2).toList()); 2 } else 0
        }
        assertEquals(4, queue.pendingBytes)
        queue.enqueueAndDrain(byteArrayOf(7, 8)) { bytes, offset, length ->
            heard.addAll(bytes.copyOfRange(offset, offset + length).toList()); length
        }
        assertEquals((1..8).map { it.toByte() }, heard)
        assertEquals(0, queue.pendingBytes)
    }

    @Test
    fun `stalled output retains only newest complete PCM frames within latency budget`() {
        val queue = PcmWriteQueue(8, 4)
        queue.enqueueAndDrain(ByteArray(8) { 1 }) { _, _, _ -> 0 }
        queue.enqueueAndDrain(ByteArray(8) { 2 }) { _, _, _ -> 0 }
        assertEquals(8, queue.pendingBytes)
        val heard = mutableListOf<Byte>()
        queue.enqueueAndDrain(byteArrayOf()) { bytes, offset, length ->
            heard.addAll(bytes.copyOfRange(offset, offset + length).toList()); length
        }
        assertEquals(List(8) { 2.toByte() }, heard)
    }

    @Test
    fun `flush clears pending PCM and the next audio drains normally`() {
        val queue = PcmWriteQueue(16, 2)
        queue.enqueueAndDrain(byteArrayOf(1, 2, 3, 4)) { _, _, _ -> 0 }
        assertEquals(4, queue.pendingBytes)

        queue.clear()
        assertEquals(0, queue.pendingBytes)
        val heard = mutableListOf<Byte>()
        queue.enqueueAndDrain(byteArrayOf(5, 6)) { bytes, offset, length ->
            heard.addAll(bytes.copyOfRange(offset, offset + length).toList())
            length
        }
        assertEquals(listOf<Byte>(5, 6), heard)
        assertEquals(0, queue.pendingBytes)
    }

    @Test
    fun `post-FLUSH gate rejects delayed UDP and accepts the declared boundary`() {
        val gate = PostFlushIngressGate()
        assertTrue(gate.accept(100, 1_000))
        gate.arm(sequence = 105, timestamp = 2_760)
        assertFalse(gate.accept(104, 2_408))
        assertTrue(gate.accept(105, 2_760))
    }

    @Test
    fun `post-PAUSE gate infers the next packet when RTP-Info is absent`() {
        val gate = PostFlushIngressGate()
        assertTrue(gate.accept(100, 1_000))
        gate.arm(sequence = null, timestamp = null)
        assertFalse(gate.accept(100, 1_000))
        assertTrue(gate.accept(101, 1_352))
    }

    @Test
    fun `sender discontinuity reanchors the next inferred pause boundary`() {
        val gate = PostFlushIngressGate()
        assertTrue(gate.accept(5_000, 10_000))
        gate.reanchor(100, 20_000)
        gate.arm(sequence = null, timestamp = null)
        assertFalse(gate.accept(100, 20_000))
        assertTrue(gate.accept(101, 20_352))
    }

    @Test
    fun `explicit zero RTP timestamp is a real wrap boundary`() {
        val gate = PostFlushIngressGate()
        assertTrue(gate.accept(5_000, 0xFFFF_FF00L))
        gate.arm(sequence = 5_001, timestamp = 0)
        assertFalse(gate.accept(5_000, 0xFFFF_FF00L))
        assertTrue(gate.accept(5_001, 0))
    }

    @Test
    fun `RECORD without RTP info lets the first resumed packet establish a new epoch`() {
        val gate = PostFlushIngressGate()
        assertTrue(gate.accept(5_000, 10_000))
        gate.arm(sequence = null, timestamp = null)
        assertFalse(gate.accept(100, 352))
        gate.acceptNextEpoch()
        assertTrue(gate.accept(100, 352))
        gate.arm(sequence = null, timestamp = null)
        assertFalse(gate.accept(100, 352))
        assertTrue(gate.accept(101, 704))
    }

    @Test
    fun `decoder health conceals isolated damage and fails persistent silence`() {
        val policy = DecoderHealthPolicy(
            maximumConsecutiveFailures = 3,
            maximumFramesWithoutPcm = 5
        )
        assertFalse(policy.onDecodeFailure())
        policy.onPcmProduced()
        assertFalse(policy.onDecodeFailure())
        assertFalse(policy.onDecodeFailure())
        assertTrue(policy.onDecodeFailure())

        policy.reset()
        repeat(4) { assertFalse(policy.onFrameWithoutPcm()) }
        assertTrue(policy.onFrameWithoutPcm())
        policy.onPcmProduced()
        assertFalse(policy.onFrameWithoutPcm())
    }

    @Test
    fun `software PCM volume applies unity half gain and mute in place`() {
        val unity = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        assertSame(unity, applyPcm16LeGainInPlace(unity, 1f))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), unity)

        val half = byteArrayOf(
            0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(),
            0xE8.toByte(), 0x03, 0x18, 0xFC.toByte()
        )
        assertSame(half, applyPcm16LeGainInPlace(half, 0.5f))
        assertArrayEquals(byteArrayOf(
            0x00, 0x40, 0x00, 0xC0.toByte(),
            0xF4.toByte(), 0x01, 0x0C, 0xFE.toByte()
        ), half)

        val muted = byteArrayOf(1, 2, 3, 4)
        applyPcm16LeGainInPlace(muted, 0f)
        assertArrayEquals(ByteArray(4), muted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `software PCM volume rejects incomplete samples`() {
        applyPcm16LeGainInPlace(byteArrayOf(1), 0.5f)
    }

    @Test(expected = IllegalStateException::class)
    fun `negative AudioTrack error is detected instead of silently discarding audio`() {
        PcmWriteQueue(16, 4).enqueueAndDrain(ByteArray(4)) { _, _, _ -> -6 }
    }

    @Test
    fun `production audio sink treats zero and dead object writes as fatal`() {
        assertEquals(128, requireAudioWriteResult(128))
        for (failure in listOf(0, -6)) {
            try {
                requireAudioWriteResult(failure)
                fail("Expected AudioSinkException for result $failure")
            } catch (expected: AudioSinkException) {
                assertTrue(expected.message.orEmpty().contains(failure.toString()))
            }
        }
    }

    @Test
    fun `production audio sink converts platform write exceptions into a fatal sink error`() {
        val platformFailure = IllegalStateException("dead AudioTrack")
        try {
            guardedAudioWrite { throw platformFailure }
            fail("Expected AudioSinkException")
        } catch (expected: AudioSinkException) {
            assertSame(platformFailure, expected.cause)
        }
    }

    @Test
    fun `loss concealment fades out and next PCM fades in without hard zero seams`() {
        val concealed = buildFadedPcmSilence(shortArrayOf(10_000), frames = 480, sampleRate = 48_000)
        assertEquals(10_000, sampleAt(concealed, 0).toInt())
        assertTrue(sampleAt(concealed, 239).toInt() in 1..100)
        assertEquals(0, sampleAt(concealed, 240).toInt())

        val resumed = ByteArray(480 * 2)
        for (frame in 0 until 480) {
            resumed[frame * 2] = 0x10
            resumed[frame * 2 + 1] = 0x27
        }
        applyPcmFadeInInPlace(resumed, channels = 1, sampleRate = 48_000)
        assertTrue(sampleAt(resumed, 0).toInt() in 1..100)
        assertEquals(10_000, sampleAt(resumed, 239).toInt())
        assertEquals(10_000, sampleAt(resumed, 240).toInt())
    }

    @Test
    fun `RTP payload parser strips CSRC extension and padding before decryption`() {
        val packet = ByteArray(32)
        packet[0] = 0xb1.toByte() // v2, padding, extension, one CSRC
        packet[1] = 96
        packet[19] = 1 // one four-byte extension word after the extension header
        packet[31] = 4
        assertEquals(24..27, audioRtpPayloadRange(packet, 0, packet.size))
    }

    @Test
    fun `RTP rejects timing truncated extension and invalid padding`() {
        val packet = ByteArray(20).apply { this[0] = 0x80.toByte(); this[1] = 96 }
        assertEquals(12..19, audioRtpPayloadRange(packet, 0, packet.size))
        packet[1] = 0xd4.toByte()
        assertNull(audioRtpPayloadRange(packet, 0, packet.size))
        packet[1] = 96
        packet[0] = 0x90.toByte()
        packet[15] = 127
        assertNull(audioRtpPayloadRange(packet, 0, packet.size))
        packet[0] = 0xa0.toByte()
        packet[19] = 21
        assertNull(audioRtpPayloadRange(packet, 0, packet.size))
        assertNull(audioRtpPayloadRange(packet, 8, 30))
    }

    @Test
    fun `complete audio buffering budget stays below the latency contract`() {
        for (rate in listOf(44100, 48000)) {
            for (samples in listOf(352, 480, 1024)) {
                val mirrorQueue = AudioStreamServer.queueCapacityPackets(
                    AudioStreamServer.CT_AAC_ELD, rate, samples, 0
                )
                val mirrorReorder = AudioStreamServer.packetBudget(rate, samples,
                    AudioStreamServer.reorderBudgetMillis(AudioStreamServer.CT_AAC_ELD))
                assertTrue((mirrorQueue + mirrorReorder).toDouble() * samples * 1000 / rate <= 150)
                val musicQueue = AudioStreamServer.queueCapacityPackets(
                    AudioStreamServer.CT_ALAC, rate, samples, latencyMaxSamples = rate * 2
                )
                val packetMillis = samples * 1_000.0 / rate
                val worstCaseMillis = musicQueue * packetMillis +
                    AudioStreamServer.reorderBudgetMillis(AudioStreamServer.CT_ALAC) +
                    AudioStreamServer.OUTPUT_CAPACITY_BUDGET_MILLIS
                val budgetWithHeadroom = AudioStreamServer.LOW_LATENCY_CONTRACT_MILLIS -
                    AudioStreamServer.REQUIRED_PIPELINE_HEADROOM_MILLIS
                assertTrue(worstCaseMillis <= budgetWithHeadroom)
                assertTrue(
                    AudioStreamServer.reorderBudgetMillis(AudioStreamServer.CT_ALAC) >=
                        AudioStreamServer.reorderBudgetMillis(AudioStreamServer.CT_AAC_ELD)
                )
            }
        }

        assertEquals(
            12,
            AudioStreamServer.queueCapacityPackets(
                AudioStreamServer.CT_ALAC, 44100, 352, latencyMaxSamples = 88200
            )
        )
    }

    @Test
    fun `resend channel uses advertised endpoint and exact RAOP packet`() {
        val advertised = InetSocketAddress("192.168.1.77", 62751)
        val observed = InetSocketAddress("192.168.1.77", 50000)
        var sentBytes: ByteArray? = null
        var sentTarget: java.net.SocketAddress? = null
        val channel = RaopResendChannel(advertised) { bytes, target ->
            sentBytes = bytes
            sentTarget = target
        }

        channel.learnFallback(observed)
        assertTrue(channel.request(ResendRange(0x1234, 2)))
        assertEquals(advertised, sentTarget)
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0xD5.toByte(), 0, 0, 0x12, 0x34, 0, 2),
            requireNotNull(sentBytes)
        )
        assertEquals(1, channel.successfulRequests)
        assertEquals(0, channel.failedRequests)
    }

    @Test
    fun `resend falls back to observed same-sender port after advertised endpoint is silent`() {
        val advertised = InetSocketAddress("192.168.1.77", 62751)
        val observed = InetSocketAddress("192.168.1.77", 50123)
        val targets = mutableListOf<java.net.SocketAddress>()
        val channel = RaopResendChannel(advertised) { _, target -> targets += target }
        channel.learnFallback(observed)

        repeat(3) { channel.request(ResendRange(101, 1)) }
        assertEquals(listOf(advertised, advertised, observed), targets)

        val confirmed = InetSocketAddress("192.168.1.77", 50124)
        channel.confirmReplySource(confirmed)
        channel.request(ResendRange(102, 1))
        assertEquals(confirmed, targets.last())
    }

    @Test
    fun `confirmed advertised replies reset fallback silence counting for each gap`() {
        val advertised = InetSocketAddress("192.168.1.77", 62751)
        val observed = InetSocketAddress("192.168.1.77", 50123)
        val targets = mutableListOf<java.net.SocketAddress>()
        val channel = RaopResendChannel(advertised) { _, target -> targets += target }
        channel.learnFallback(observed)

        channel.request(ResendRange(101, 1))
        channel.confirmReplySource(advertised)
        channel.request(ResendRange(102, 1))
        channel.confirmReplySource(advertised)
        channel.request(ResendRange(103, 1))

        assertEquals(listOf(advertised, advertised, advertised), targets)
    }

    @Test
    fun `omitted control port learns only the expected sender address`() {
        val senderAddress = java.net.InetAddress.getByName("192.168.1.77")
        val sender = InetSocketAddress(senderAddress, 50123)
        val stalePeer = InetSocketAddress("192.168.1.99", 50123)
        val targets = mutableListOf<java.net.SocketAddress>()
        val channel = RaopResendChannel(null, expectedPeer = senderAddress) { _, target ->
            targets += target
        }

        assertFalse(channel.learnFallback(stalePeer))
        assertFalse(channel.isReady())
        assertTrue(channel.learnFallback(sender))
        assertTrue(channel.request(ResendRange(101, 1)))
        assertEquals(listOf(sender), targets)
    }

    @Test
    fun `resend ignores an observed endpoint from a different host`() {
        val advertised = InetSocketAddress("192.168.1.77", 62751)
        val attacker = InetSocketAddress("192.168.1.99", 50123)
        val targets = mutableListOf<java.net.SocketAddress>()
        val channel = RaopResendChannel(advertised) { _, target -> targets += target }
        channel.learnFallback(attacker)
        repeat(3) { channel.request(ResendRange(101, 1)) }
        assertEquals(listOf(advertised, advertised, advertised), targets)
    }

    @Test
    fun `lost resend request retries at a bounded interval without a storm`() {
        var now = 1_000_000_000L
        val policy = ResendRetryPolicy(
            retryIntervalNanos = 30_000_000L,
            maxAttempts = 3,
            clockNanos = { now }
        )

        assertEquals(ResendRange(101, 1), policy.request(101, 1))
        assertNull(policy.request(101, 1))
        now += 30_000_000L
        assertEquals(ResendRange(101, 2), policy.request(101, 2))
        now += 30_000_000L
        assertEquals(ResendRange(101, 1), policy.request(101, 1))
        now += 30_000_000L
        assertNull(policy.request(101, 1))

        policy.resolved(102)
        assertEquals(ResendRange(105, 1), policy.request(105, 1))
    }

    @Test
    fun `resend waits for a destination then uses a late observed fallback`() {
        val observed = InetSocketAddress("192.168.1.77", 50123)
        var sentTarget: java.net.SocketAddress? = null
        val channel = RaopResendChannel(null) { _, target -> sentTarget = target }

        assertFalse(channel.isReady())
        assertFalse(channel.request(ResendRange(101, 1)))
        assertEquals(0, channel.failedRequests)
        assertTrue(channel.learnFallback(observed))
        assertTrue(channel.isReady())
        assertTrue(channel.request(ResendRange(101, 1)))
        assertEquals(observed, sentTarget)
    }

    @Test
    fun `resend range contains only the first contiguous hole including wraparound`() {
        val buffered = (102..116).toSet()
        assertEquals(
            ResendRange(101, 1),
            AudioStreamServer.firstMissingRange(101, 116, buffered::contains)
        )
        assertEquals(
            ResendRange(101, 2),
            AudioStreamServer.firstMissingRange(101, 103, setOf(103)::contains)
        )
        assertEquals(
            ResendRange(0xFFFF, 1),
            AudioStreamServer.firstMissingRange(0xFFFF, 0, setOf(0)::contains)
        )
    }

    @Test
    fun `music prime honors sender minimum within actual track capacity`() {
        val prime = AudioStreamServer.initialMusicPrimeBytes(
            minBufferBytes = 25_344,
            maximumPrimeBytes = 49_280,
            latencyMinSamples = 11_025,
            channels = 2
        )
        assertEquals(44_100, prime)
        assertEquals(250, prime * 1_000 / (44_100 * 2 * 2))

        assertEquals(
            49_280,
            AudioStreamServer.initialMusicPrimeBytes(25_344, 49_280, 88_200, 2)
        )
    }

    @Test
    fun `one missing packet expires by wall clock and releases buffered audio`() {
        var now = 0L
        val reorder = RtpReorderBuffer(maximumTrackedPackets = 128, holdNanos = 90_000_000L) { now }
        assertArrayEquals(byteArrayOf(100), reorder.offer(100, 1_000, byteArrayOf(100)).encodedFrames.single())

        val waiting = reorder.offer(102, 1_704, byteArrayOf(102))
        assertEquals(ResendRange(101, 1), waiting.gap)
        assertTrue(waiting.encodedFrames.isEmpty())
        now = 89_999_999L
        assertFalse(reorder.expireGapIfDue().expiredGap)
        now = 90_000_000L
        val released = reorder.expireGapIfDue()
        assertTrue(released.expiredGap)
        assertEquals(1, released.silencePackets)
        assertArrayEquals(byteArrayOf(102), released.encodedFrames.single())
        assertNull(released.gap)
    }

    @Test
    fun `reorder handles sequence wrap flush and forward discontinuity in bounded work`() {
        val reorder = RtpReorderBuffer(maximumTrackedPackets = 128, holdNanos = 90_000_000L)
        assertArrayEquals(byteArrayOf(1), reorder.offer(0xFFFF, 1_000, byteArrayOf(1)).encodedFrames.single())
        assertEquals(ResendRange(0, 1), reorder.offer(1, 1_704, byteArrayOf(2)).gap)

        reorder.clear()
        assertArrayEquals(byteArrayOf(3), reorder.offer(50, 2_000, byteArrayOf(3)).encodedFrames.single())
        val jumped = reorder.offer(500, 20_000, byteArrayOf(4))
        assertTrue(jumped.resynchronized)
        assertArrayEquals(byteArrayOf(4), jumped.encodedFrames.single())
        assertEquals(0, jumped.silencePackets)
    }

    @Test
    fun `backward sender restart resynchronizes when RTP time advances`() {
        val reorder = RtpReorderBuffer(maximumTrackedPackets = 128, holdNanos = 90_000_000L)
        reorder.offer(5_000, 10_000, byteArrayOf(1))
        val reset = reorder.offer(100, 10_352, byteArrayOf(2))
        assertTrue(reset.resynchronized)
        assertArrayEquals(byteArrayOf(2), reset.encodedFrames.single())
    }

    @Test
    fun `burst does not expire a recoverable gap before its wall deadline`() {
        var now = 0L
        val reorder = RtpReorderBuffer(maximumTrackedPackets = 128, holdNanos = 90_000_000L) { now }
        reorder.offer(100, 1_000, byteArrayOf(100))
        for (seq in 102..116) {
            val output = reorder.offer(seq, 1_000L + (seq - 100L) * 352L, byteArrayOf(seq.toByte()))
            assertFalse(output.expiredGap)
            assertEquals(ResendRange(101, 1), output.gap)
        }
        now = 90_000_000L
        val released = reorder.expireGapIfDue()
        assertTrue(released.expiredGap)
        assertEquals(15, released.encodedFrames.size)
    }

    @Test
    fun `one deadline drains sparse buffered islands with global concealment cap`() {
        var now = 0L
        val reorder = RtpReorderBuffer(maximumTrackedPackets = 128, holdNanos = 90_000_000L) { now }
        reorder.offer(100, 1_000, byteArrayOf(100))
        reorder.offer(102, 1_704, byteArrayOf(102))
        reorder.offer(104, 2_408, byteArrayOf(104))
        reorder.offer(106, 3_112, byteArrayOf(106))
        now = 90_000_000L
        val released = reorder.expireGapIfDue()
        assertTrue(released.expiredGap)
        assertEquals(3, released.silencePackets)
        assertEquals(listOf<Byte>(102, 104, 106), released.encodedFrames.map { it.single() })
        assertEquals(
            listOf<Int>(-1, 102, -1, 104, -1, 106),
            released.playoutFrames.map { frame ->
                when (frame) {
                    is ReorderedPacket.Encoded -> frame.bytes.single().toInt() and 0xFF
                    ReorderedPacket.Concealment -> -1
                }
            }
        )
        assertNull(released.gap)
    }

    @Test
    fun `late partial recovery cannot extend the original gap deadline`() {
        var now = 0L
        val reorder = RtpReorderBuffer(maximumTrackedPackets = 128, holdNanos = 90_000_000L) { now }
        reorder.offer(100, 1_000, byteArrayOf(100))
        reorder.offer(102, 1_704, byteArrayOf(102))
        reorder.offer(104, 2_408, byteArrayOf(104))

        now = 80_000_000L
        val partial = reorder.offer(101, 1_352, byteArrayOf(101))
        assertEquals(listOf<Byte>(101, 102), partial.encodedFrames.map { it.single() })
        assertEquals(ResendRange(103, 1), partial.gap)

        now = 90_000_000L
        val released = reorder.expireGapIfDue()
        assertTrue(released.expiredGap)
        assertEquals(1, released.silencePackets)
        assertArrayEquals(byteArrayOf(104), released.encodedFrames.single())
    }

    @Test
    fun `reorderer owns duplicate detection without masking recent sequence reset`() {
        val reorder = RtpReorderBuffer(maximumTrackedPackets = 128, holdNanos = 90_000_000L)
        val first = reorder.offer(5_000, 10_000, byteArrayOf(1))
        assertFalse(first.duplicateOrLate)
        assertTrue(reorder.offer(5_000, 10_000, byteArrayOf(1)).duplicateOrLate)
        val reset = reorder.offer(5_000 - 64, 10_352, byteArrayOf(2))
        assertTrue(reset.resynchronized)
        assertFalse(reset.duplicateOrLate)
    }

    @Test
    fun `music AudioTrack starts only after prime and raises a bounded rebuffer target`() {
        val policy = MusicPlayoutPolicy(
            initialPrimeBytes = 100,
            maximumPrimeBytes = 180,
            rebufferStepBytes = 40
        )
        policy.setUnderrunBaseline(0)
        assertFalse(policy.onBytesWritten(99))
        assertTrue(policy.onBytesWritten(1))
        assertTrue(policy.started)

        assertTrue(policy.onUnderrunCount(1))
        assertFalse(policy.started)
        assertEquals(140, policy.targetPrimeBytes)
        assertFalse(policy.onBytesWritten(139))
        assertTrue(policy.onBytesWritten(1))

        assertTrue(policy.onUnderrunCount(2))
        assertEquals(180, policy.targetPrimeBytes)
        assertFalse(policy.onUnderrunCount(2))
    }

    @Test
    fun `music playout can start safely when a stopped AudioTrack reports no remaining space`() {
        val policy = MusicPlayoutPolicy(
            initialPrimeBytes = 100,
            maximumPrimeBytes = 180,
            rebufferStepBytes = 40
        )
        assertFalse(policy.onBytesWritten(80))
        assertTrue(policy.startWithPrimedData())
        assertTrue(policy.started)
        assertFalse(policy.startWithPrimedData())
    }
}
