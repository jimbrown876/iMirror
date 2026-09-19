package dev.imirror.receiver.airplay

import org.junit.Assert.*
import org.junit.Test

class ControlResponseWriterTest {
    @Test fun `empty persistent HTTP reply has length zero but no RTSP CSeq`() {
        val wire = String(encodeControlResponse(RtspResponse(200, "OK", protocol = "HTTP/1.1"), 99))
        assertTrue(wire.contains("Content-Length: 0\r\n"))
        assertFalse(wire.contains("CSeq:"))
        assertTrue(wire.endsWith("\r\n\r\n"))
    }

    @Test fun `reverse upgrade omits content length and preserves upgrade headers`() {
        val wire = String(encodeControlResponse(RtspResponse(101, "Switching Protocols", protocol = "HTTP/1.1",
            headers = mapOf("Upgrade" to "PTTH/1.0", "Connection" to "Upgrade")), 0))
        assertFalse(wire.contains("Content-Length:"))
        assertTrue(wire.contains("Upgrade: PTTH/1.0\r\n"))
    }

    @Test fun `RTSP sequence values and binary bodies remain isolated per response`() {
        val body = byteArrayOf(0, -1, 13, 10, 42)
        val response = RtspResponse(200, "OK", bodyBytes = body)
        val first = encodeControlResponse(response, 11)
        val second = encodeControlResponse(response, 22)
        assertTrue(String(first).contains("CSeq: 11\r\n"))
        assertTrue(String(second).contains("CSeq: 22\r\n"))
        assertTrue(String(second).contains("Content-Length: 5\r\n"))
        assertArrayEquals(body, second.takeLast(5).toByteArray())
    }
}
