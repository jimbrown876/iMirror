package dev.imirror.receiver.airplay

import dev.imirror.receiver.airplay.handshake.AudioStreamServer
import org.junit.Assert.*
import org.junit.Test

class AudioBufferingTest {
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
    fun `mirroring remains below 150ms while music gets bounded weak wifi headroom`() {
        for (rate in listOf(44100, 48000)) {
            for (samples in listOf(352, 480, 1024)) {
                val mirrorQueue = AudioStreamServer.packetBudget(rate, samples,
                    AudioStreamServer.queueBudgetMillis(AudioStreamServer.CT_AAC_ELD))
                val mirrorReorder = AudioStreamServer.packetBudget(rate, samples,
                    AudioStreamServer.reorderBudgetMillis(AudioStreamServer.CT_AAC_ELD))
                assertTrue((mirrorQueue + mirrorReorder).toDouble() * samples * 1000 / rate <= 150)

                val musicQueue = AudioStreamServer.packetBudget(rate, samples,
                    AudioStreamServer.queueBudgetMillis(AudioStreamServer.CT_ALAC))
                val musicReorder = AudioStreamServer.packetBudget(rate, samples,
                    AudioStreamServer.reorderBudgetMillis(AudioStreamServer.CT_ALAC))
                assertTrue((musicQueue + musicReorder).toDouble() * samples * 1000 / rate <= 225)
                assertTrue(musicQueue >= mirrorQueue && musicReorder >= mirrorReorder)
            }
        }
    }
}
