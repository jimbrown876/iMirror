package dev.imirror.receiver.airplay

import dev.imirror.receiver.util.Logger
import java.io.InputStream
import java.util.Locale
import java.util.TreeMap

/**
 * RtspRequestReader parses RTSP/HTTP-style requests from the AirPlay control socket.
 *
 * The reader consumes bytes directly from the socket so the caller can safely switch
 * to binary RTP interleaved frames after RTSP RECORD without losing buffered data.
 */
internal class RtspRequestReader(
    private val maxMessageBytes: Int,
    private val maxPhotoBytes: Int,
    private val maxArtworkBytes: Int = 5 * 1024 * 1024
) {
    /**
     * Reads one complete RTSP or AirPlay photo request from [inputStream].
     *
     * Returns null on clean EOF, malformed input, or payloads over the configured limits.
     */
    fun read(inputStream: InputStream): RtspRequest? {
        val requestLine = readLine(inputStream) ?: return null
        if (requestLine.isBlank()) return read(inputStream)

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            Logger.w("Malformed RTSP request line: '$requestLine'")
            return null
        }

        val headers = readHeaders(inputStream, requestLine.length) ?: return null
        val method = parts[0]
        val uri = parts[1]
        val protocol = parts[2]
        val bodyBytes = readBody(inputStream, method, uri, headers) ?: return null

        return RtspRequest(
            method = method,
            uri = uri,
            headers = headers,
            body = String(bodyBytes, Charsets.UTF_8),
            bodyBytes = bodyBytes,
            protocol = protocol
        )
    }

    private fun readHeaders(inputStream: InputStream, requestLineBytes: Int): Map<String, String>? {
        // HTTP/RTSP field names are case-insensitive, including framing and MIME fields.
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        var totalBytes = requestLineBytes

        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isEmpty()) return headers

            totalBytes += line.length
            if (totalBytes > maxMessageBytes) {
                Logger.w("RTSP message too large — rejecting")
                return null
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val name = line.substring(0, colonIndex).trim()
                if (name.equals("Content-Length", ignoreCase = true) && headers.containsKey(name)) {
                    Logger.w("Duplicate Content-Length — rejecting ambiguous request framing")
                    return null
                }
                headers[name] = line.substring(colonIndex + 1).trim()
            }
        }
    }

    private fun readBody(
        inputStream: InputStream,
        method: String,
        uri: String,
        headers: Map<String, String>
    ): ByteArray? {
        // AirPlay sends fixed-length bodies. Do not treat malformed lengths as empty or let an
        // unsupported transfer encoding leave bytes to be mistaken for the next RTSP request.
        if (headers.containsKey("Transfer-Encoding")) {
            Logger.w("Unsupported Transfer-Encoding — rejecting request")
            return null
        }
        val rawLength = headers["Content-Length"]
        val contentLength = if (rawLength == null) 0 else {
            if (rawLength.isEmpty() || rawLength.any { it !in '0'..'9' }) return null
            rawLength.toIntOrNull() ?: return null
        }
        val contentType = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val bodyLimit = when {
            method == "PUT" && uri.substringBefore("?") == PhotoHandler.PHOTO_PATH -> maxPhotoBytes
            // Album covers arrive during an existing audio session, not at the /photo endpoint.
            // A typical ~95KB JPEG must not tear down music through the 64KB control-message cap.
            method == "SET_PARAMETER" && contentType in setOf("image/jpeg", "image/png") -> maxArtworkBytes
            else -> maxMessageBytes
        }

        if (contentLength > bodyLimit) {
            Logger.w("Request body too large ($contentLength bytes) — rejecting")
            return null
        }
        if (contentLength == 0) return ByteArray(0)

        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = inputStream.read(buf, read, contentLength - read)
            if (n == -1) return null
            read += n
        }
        return buf
    }

    private fun readLine(inputStream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inputStream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > maxMessageBytes) return null
        }
    }
}
