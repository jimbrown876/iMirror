package dev.imirror.receiver.airplay

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PIN mode must cover all media/control paths, not just the usual macOS handshake. */
class RtspAuthorizationTest {
    @Test
    fun `accepted quiet legacy media does not retain the handshake idle timeout`() {
        var videoAccepted = false
        var photoAccepted = false
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {},
            onVideoPlay = { _, _ -> videoAccepted = true },
            onPhotoReceived = { _, _ -> photoAccepted = true }
        )
        val play = RtspRequest("POST", "/play", emptyMap(), "Content-Location: http://sender/movie.mp4\r\n")
        assertTrue(handler.establishesControlSession(play, handler.routeRequest(play)))
        assertTrue(videoAccepted)
        val photo = RtspRequest("PUT", "/photo", mapOf("Content-Type" to "image/jpeg"), "",
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 1))
        assertTrue(handler.establishesControlSession(photo, handler.routeRequest(photo)))
        assertTrue(photoAccepted)
    }

    @Test
    fun `discovery and rejected media retain the handshake idle timeout`() {
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {}
        )
        val options = RtspRequest("OPTIONS", "*", emptyMap(), "")
        assertFalse(handler.establishesControlSession(options, handler.routeRequest(options)))
        val badPlay = RtspRequest("POST", "/play", emptyMap(), "")
        assertEquals(400, handler.routeRequest(badPlay).statusCode)
        assertFalse(handler.establishesControlSession(badPlay, handler.routeRequest(badPlay)))
        val badPhoto = RtspRequest("PUT", "/photo", emptyMap(), "not an image")
        assertEquals(400, handler.routeRequest(badPhoto).statusCode)
        assertFalse(handler.establishesControlSession(badPhoto, handler.routeRequest(badPhoto)))
    }

    @Test
    fun `unverified connection cannot bypass PIN through alternate playback endpoints`() {
        var callbackInvoked = false
        val handler = RtspHandler(
            context = mockk(relaxed = true),
            videoSurfaceProvider = { null },
            onStreamingStarted = { callbackInvoked = true },
            onStreamingStopped = { callbackInvoked = true },
            onPhotoReceived = { _, _ -> callbackInvoked = true },
            onVideoPlay = { _, _ -> callbackInvoked = true },
            onVideoStop = { callbackInvoked = true },
            onRemoteControlInfo = { _, _ -> callbackInvoked = true },
            pinAuthEnabled = true
        )
        val endpoints = listOf(
            "ANNOUNCE" to "rtsp://receiver/session", "SETUP" to "rtsp://receiver/session",
            "RECORD" to "rtsp://receiver/session", "SET_PARAMETER" to "rtsp://receiver/session",
            "TEARDOWN" to "rtsp://receiver/session", "PUT" to "/photo", "DELETE" to "/photo",
            "POST" to "/play", "POST" to "/stop", "POST" to "/rate?value=0",
            "POST" to "/scrub?position=4", "POST" to "/fp-setup"
        )
        for ((method, uri) in endpoints) {
            val response = handler.routeRequest(RtspRequest(
                method, uri, mapOf("Active-Remote" to "123", "DACP-ID" to "spoofed"), ""
            ))
            assertEquals("$method $uri must require verified controller identity", 470, response.statusCode)
        }
        assertFalse(callbackInvoked)
    }

    @Test
    fun `discovery remains available without accepting an unauthenticated reverse controller`() {
        var remoteControlAccepted = false
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {},
            onRemoteControlInfo = { _, _ -> remoteControlAccepted = true }, pinAuthEnabled = true
        )
        assertEquals(200, handler.routeRequest(RtspRequest(
            "OPTIONS", "*", mapOf("Active-Remote" to "123", "DACP-ID" to "spoofed"), ""
        )).statusCode)
        assertFalse(remoteControlAccepted)
    }
}
