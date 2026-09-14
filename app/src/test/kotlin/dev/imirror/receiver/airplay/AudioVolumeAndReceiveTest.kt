package dev.imirror.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import dev.imirror.receiver.airplay.handshake.AudioStreamServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.DatagramPacket
import java.net.DatagramSocket
import org.junit.Assert.*
import org.junit.Test

class AudioVolumeAndReceiveTest {
    @Test
    fun `volume reaches legacy and realtime players and is retained before setup`() {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        val receiver = AirPlayReceiver(context, videoSurfaceProvider = { null }, onStateChanged = {})
        val callback = AirPlayReceiver::class.java.getDeclaredMethod("updateAudioVolume", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        callback.invoke(receiver, -12f)
        assertEquals(-12f, field(receiver, "senderVolumeDb") as Float, 0f)
        val legacy = mockk<AudioPlayer>(relaxed = true)
        val realtime = mockk<AudioStreamServer>(relaxed = true)
        setField(receiver, "audioPlayer", legacy)
        setField(receiver, "audioServer", realtime)
        callback.invoke(receiver, -144f)
        verify { legacy.setVolume(-144f) }
        verify { realtime.setVolume(-144f) }
        callback.invoke(receiver, Float.NaN)
        assertEquals(-144f, field(receiver, "senderVolumeDb") as Float, 0f)
        verify(exactly = 1) { legacy.setVolume(any()) }
    }

    @Test
    fun `legacy player retains pre-initialization volume and ignores invalid values`() {
        val player = AudioPlayer()
        player.setVolume(-6f)
        val gain = field(player, "volumeGain") as Float
        assertEquals(0.5011872f, gain, 0.00001f)
        player.setVolume(Float.POSITIVE_INFINITY)
        assertEquals(gain, field(player, "volumeGain") as Float, 0f)
    }

    @Test
    fun `AirPlay decibels map to amplitude and explicit mute remains silent`() {
        assertEquals(1f, airplayVolumeGain(0f), 0f)
        assertEquals(0.1f, airplayVolumeGain(-20f), 0.000001f)
        assertEquals(0.0316228f, airplayVolumeGain(-30f), 0.000001f)
        assertEquals(0f, airplayVolumeGain(-144f), 0f)
    }

    @Test
    fun `short UDP packet cannot truncate the following larger audio frame`() {
        val socket = mockk<DatagramSocket>()
        val packet = DatagramPacket(ByteArray(2048), 2048)
        val capacities = mutableListOf<Int>()
        var receiveNumber = 0
        every { socket.receive(packet) } answers {
            capacities.add(packet.length)
            packet.length = if (receiveNumber++ == 0) 32 else 1408
        }
        receiveAudioDatagram(socket, packet)
        assertEquals(32, packet.length)
        receiveAudioDatagram(socket, packet)
        assertEquals(listOf(2048, 2048), capacities)
        assertEquals(1408, packet.length)
    }

    @Test
    fun `full session teardown clears a photo takeover`() {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        var clears = 0
        val receiver = AirPlayReceiver(
            context,
            videoSurfaceProvider = { null },
            onStateChanged = {},
            onPhotoCleared = { clears++ }
        )
        AirPlayReceiver::class.java.getDeclaredMethod("releaseMediaComponents")
            .apply { isAccessible = true }
            .invoke(receiver)
        assertEquals(1, clears)
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun setField(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
}
