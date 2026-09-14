package dev.imirror.receiver.airplay

import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

class NowPlayingControlTest {
    @Test
    fun `real sized artwork then repeated volume and feedback keep the session alive`() {
        val artwork = ByteArray(95_330) { (it % 256).toByte() }
        var receivedArtwork: ByteArray? = null
        var stopped = false
        val volumes = mutableListOf<Float>()
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = { stopped = true },
            onArtwork = { receivedArtwork = it }, onVolume = { volumes.add(it) }
        )
        val wire = "SET_PARAMETER rtsp://receiver/session RTSP/1.0\r\nContent-Type: image/jpeg\r\nContent-Length: ${artwork.size}\r\n\r\n".toByteArray() + artwork +
            "SET_PARAMETER rtsp://receiver/session RTSP/1.0\r\nContent-Type: text/parameters\r\nContent-Length: 16\r\n\r\nvolume: -1.875\r\n".toByteArray()
        val reader = RtspRequestReader(64 * 1024, 25 * 1024 * 1024)
        val input = ByteArrayInputStream(wire)
        assertEquals(200, handler.routeRequest(requireNotNull(reader.read(input))).statusCode)
        assertArrayEquals(artwork, receivedArtwork)
        assertEquals(200, handler.routeRequest(requireNotNull(reader.read(input))).statusCode)
        assertEquals(listOf(-1.875f), volumes)
        repeat(100) {
            assertEquals(200, handler.routeRequest(RtspRequest("SET_PARAMETER", "rtsp://receiver/session",
                mapOf("Content-Type" to "text/parameters"), "volume: -15\r\n")).statusCode)
            assertEquals(200, handler.routeRequest(RtspRequest("POST", "/feedback", emptyMap(), "")).statusCode)
        }
        assertFalse(stopped)
        assertArrayEquals(artwork, receivedArtwork)
        assertNull(reader.read(input))
    }

    @Test
    fun `non finite volume never reaches output and responses are locale independent`() {
        val values = mutableListOf<Float>()
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {}, onVolume = { values.add(it) }
        )
        for (value in listOf("NaN", "Infinity", "-Infinity", "-1.875")) {
            handler.routeRequest(RtspRequest("SET_PARAMETER", "*", emptyMap(), "volume: $value\r\n"))
        }
        assertEquals(listOf(-1.875f), values)
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("volume: -1.875000\r\n", handler.routeRequest(
                RtspRequest("GET_PARAMETER", "*", emptyMap(), "volume\r\n")
            ).body)
        } finally {
            Locale.setDefault(original)
        }
    }
}
