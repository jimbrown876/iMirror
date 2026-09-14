package dev.imirror.receiver.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class RtspRequestReaderTest {
    private fun reader(artworkLimit: Int = 5 * 1024 * 1024) = RtspRequestReader(
        maxMessageBytes = 65_536, maxPhotoBytes = 25 * 1024 * 1024, maxArtworkBytes = artworkLimit
    )

    @Test
    fun `95330-byte album cover followed by volume is read without closing or consuming the next request`() {
        val artwork = ByteArray(95_330) { (it % 256).toByte() }.also {
            it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[2] = 0xff.toByte()
        }
        val volume = "volume: -1.875\r\n".toByteArray()
        val wire = request("SET_PARAMETER", "image/jpeg", artwork, cseq = 10) +
            request("SET_PARAMETER", "text/parameters", volume, cseq = 11)
        // Network reads are not guaranteed to return a whole image in one call.
        val input = shortReads(wire)
        val reader = reader()
        val cover = reader.read(input)!!
        assertEquals("SET_PARAMETER", cover.method)
        assertEquals("10", cover.headers["CSeq"])
        assertArrayEquals(artwork, cover.bodyBytes)
        val next = reader.read(input)!!
        assertEquals("11", next.headers["CSeq"])
        assertEquals("volume: -1.875\r\n", next.body)
        assertNull(reader.read(input))
    }

    @Test
    fun `mixed-case artwork headers and MIME parameters preserve framing and header lookup`() {
        val payload = ByteArray(95_330) { 7 }
        val header = "SET_PARAMETER rtsp://tv/session RTSP/1.0\r\n" +
            "cOnTeNt-TyPe: IMAGE/PNG; name=cover.png\r\ncontent-length: ${payload.size}\r\ncseq: 8\r\n\r\n"
        val result = reader().read(ByteArrayInputStream(header.toByteArray() + payload))!!
        assertArrayEquals(payload, result.bodyBytes)
        assertEquals("8", result.headers["CSeq"])
        assertEquals("IMAGE/PNG; name=cover.png", result.headers["Content-Type"])
    }

    @Test
    fun `larger artwork allowance applies only to supported artwork on SET_PARAMETER`() {
        val payload = ByteArray(65_537)
        val rejected = listOf(
            "SET_PARAMETER" to "text/parameters", "SET_PARAMETER" to "application/x-dmap-tagged",
            "SET_PARAMETER" to "image/svg+xml", "POST" to "image/jpeg", "ANNOUNCE" to "image/png"
        )
        rejected.forEach { (method, type) ->
            assertNull("$method $type must retain the control cap",
                reader().read(ByteArrayInputStream(request(method, type, payload))))
        }
        assertNotNull(reader().read(ByteArrayInputStream(request("SET_PARAMETER", "image/png", payload))))
    }

    @Test
    fun `artwork cap is inclusive and oversize is rejected before body allocation or reading`() {
        val payload = ByteArray(95_330)
        assertNotNull(reader(95_330).read(ByteArrayInputStream(request("SET_PARAMETER", "image/jpeg", payload))))
        val oversizedHeader = "SET_PARAMETER rtsp://tv/session RTSP/1.0\r\n" +
            "Content-Type: image/jpeg\r\nContent-Length: 5242881\r\n\r\n"
        assertNull(reader().read(headerOnly(oversizedHeader)))
    }

    @Test
    fun `existing photo allowance remains restricted to the photo endpoint`() {
        val payload = ByteArray(95_330)
        assertNotNull(reader().read(ByteArrayInputStream(request("PUT", "image/jpeg", payload, uri = "/photo?asset=1"))))
        assertNull(reader().read(ByteArrayInputStream(request("PUT", "image/jpeg", payload, uri = "/unrelated"))))
        val overPhotoLimit = "PUT /photo HTTP/1.1\r\nContent-Length: 26214401\r\n\r\n"
        assertNull(reader().read(headerOnly(overPhotoLimit)))
    }

    @Test
    fun `empty artwork clear and following control request remain separate`() {
        val input = ByteArrayInputStream(request("SET_PARAMETER", "image/jpeg", byteArrayOf()) +
            request("GET_PARAMETER", "text/parameters", "volume\r\n".toByteArray()))
        val reader = reader()
        assertEquals(0, reader.read(input)!!.bodyBytes.size)
        assertEquals("GET_PARAMETER", reader.read(input)!!.method)
        assertNull(reader.read(input))
    }

    @Test
    fun `truncated artwork fails without returning a partial image`() {
        val wire = request("SET_PARAMETER", "image/jpeg", ByteArray(95_330))
        assertNull(reader().read(ByteArrayInputStream(wire.copyOf(wire.size - 1))))
    }

    @Test
    fun `invalid or ambiguous framing is rejected instead of interpreting body as another request`() {
        val invalidHeaders = listOf(
            "Content-Length: -1", "Content-Length: +1", "Content-Length: many",
            "Content-Length: 2147483648", "Content-Length: 9999999999999999999999",
            "Content-Length: 1\r\ncontent-length: 2", "Content-Length: 1\r\nContent-Length: 1",
            "Transfer-Encoding: chunked", "Content-Length: 1\r\ntransfer-encoding: chunked"
        )
        invalidHeaders.forEach { framing ->
            val header = "SET_PARAMETER rtsp://tv/session RTSP/1.0\r\n$framing\r\n\r\n"
            assertNull(framing, reader().read(headerOnly(header)))
        }
    }

    private fun request(method: String, type: String, body: ByteArray, cseq: Int = 1,
                        uri: String = "rtsp://tv/session"): ByteArray =
        ("$method $uri RTSP/1.0\r\nCSeq: $cseq\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray() + body

    private fun shortReads(bytes: ByteArray): InputStream = object : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, 337))
    }

    /** Any attempt to consume a rejected body would fail the test. */
    private fun headerOnly(header: String): InputStream = object : InputStream() {
        private val bytes = ByteArrayInputStream(header.toByteArray())
        override fun read(): Int {
            check(bytes.available() > 0) { "Rejected request attempted to read its body" }
            return bytes.read()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            error("Rejected request attempted to read its body")
    }
}
