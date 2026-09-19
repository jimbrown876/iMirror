package dev.imirror.receiver.airplay

import dev.imirror.receiver.airplay.handshake.PlistCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class VideoCompanionProtocolTest {
    @Test fun `HTTP video capabilities do not claim unsupported protected video or HLS proxying`() {
        assertEquals(0x203L, HTTP_VIDEO_FEATURES)
        assertEquals(0L, HTTP_VIDEO_FEATURES and ((1L shl 2) or (1L shl 4) or (1L shl 12)))
    }

    private fun request(method: String = "POST", path: String = "/reverse", protocol: String = "HTTP/1.1",
                        headers: Map<String, String> = mapOf("X-Apple-Session-ID" to "test-session",
                            "Connection" to "keep-alive, Upgrade", "Upgrade" to "PTTH/1.0", "X-Apple-Purpose" to "event")) =
        RtspRequest(method, path, headers, "", protocol = protocol)

    @Test fun `companions cannot perform primary RTSP or photo mutations`() {
        assertTrue(isVideoCompanionRequest(request("GET", "/server-info")))
        assertTrue(isVideoCompanionRequest(request("POST", "/rate?value=0")))
        assertFalse(isVideoCompanionRequest(request("SETUP", "rtsp://receiver/session", "RTSP/1.0")))
        assertFalse(isVideoCompanionRequest(request("POST", "/fp-setup", "RTSP/1.0")))
        assertFalse(isVideoCompanionRequest(request("PUT", "/photo")))
        assertFalse(isVideoCompanionRequest(request("POST", "/pair-pin-start")))
    }

    @Test fun `reverse requires explicit upgrade and a bounded header-safe session id`() {
        assertTrue(isVideoReverseUpgrade(request()))
        assertFalse(isVideoReverseUpgrade(request(headers = emptyMap())))
        assertNull(videoSessionId(request(headers = mapOf("X-Apple-Session-ID" to "x\r\nInjected: y"))))
        assertNull(videoSessionId(request(headers = mapOf("X-Apple-Session-ID" to "x".repeat(129)))))
        assertFalse(isVideoReverseUpgrade(request("GET")))
    }

    @Test fun `events reflect actual loading playing pause stop and not unstarted route`() {
        assertNull(videoEventState(false, null))
        assertEquals("loading", videoEventState(true, PlaybackInfo(0.0, 0.0, 0.0, false)))
        assertEquals("playing", videoEventState(true, PlaybackInfo(5.0, 1.0, 1.0, true)))
        assertEquals("paused", videoEventState(true, PlaybackInfo(5.0, 1.0, 0.0, true)))
        assertEquals("stopped", videoEventState(true, null))
    }

    @Test fun `reverse event uses exact binary-safe HTTP framing`() {
        val wire = videoEventWire("test-session", "playing")
        val event = RtspRequestReader(4096, 4096).read(ByteArrayInputStream(wire))!!
        assertEquals("POST", event.method)
        assertEquals("/event", event.uri)
        assertEquals(event.bodyBytes.size.toString(), event.headers["Content-Length"])
        assertEquals("test-session", event.headers["X-Apple-Session-ID"])
        assertEquals("playing", PlistCodec.decode(event.bodyBytes)["state"])
        assertThrows(IllegalArgumentException::class.java) { videoEventWire("x\r\ny", "playing") }
    }

    @Test fun `only supported default properties are acknowledged`() {
        assertTrue(isDefaultVideoProperty("forwardEndTime", mapOf("value" to 0L, "flags" to 0L)))
        assertFalse(isDefaultVideoProperty("forwardEndTime", mapOf("value" to 300L, "flags" to 1L)))
        assertTrue(isDefaultVideoProperty("reverseEndTime", mapOf("value" to 0L, "flags" to 0L)))
        assertTrue(isDefaultVideoProperty("actionAtItemEnd", 0L))
        assertFalse(isDefaultVideoProperty("actionAtItemEnd", 1L))
        assertFalse(isDefaultVideoProperty("actionAtItemEnd", 0.5))
        assertFalse(isDefaultVideoProperty("forwardEndTime", mapOf("value" to 0.5, "flags" to 0L)))
        assertTrue(isDefaultVideoProperty("selectedMediaArray", emptyList<String>()))
        assertFalse(isDefaultVideoProperty("unknown", 0L))
        assertFalse(isDefaultVideoProperty("forwardEndTime", null))
    }
}
