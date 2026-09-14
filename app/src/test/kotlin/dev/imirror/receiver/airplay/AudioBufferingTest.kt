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
    fun `queue plus gap allowance remains below 150ms for negotiated music codecs`() {
        for (rate in listOf(44100, 48000)) {
            for (samples in listOf(352, 480, 1024)) {
                val queue = AudioStreamServer.packetBudget(rate, samples, 100)
                val reorder = AudioStreamServer.packetBudget(rate, samples, 40)
                assertTrue((queue + reorder).toDouble() * samples * 1000 / rate <= 150)
                assertTrue(queue > 0 && reorder > 0)
            }
        }
    }
}
