package dev.imirror.receiver.airplay

/** Per-request CSeq and byte-exact framing for both RTSP and persistent HTTP control sockets. */
internal fun encodeControlResponse(response: RtspResponse, cseq: Int): ByteArray {
    val wire = response.wireBody()
    val http = response.protocol.startsWith("HTTP")
    val bodyForbidden = http && (response.statusCode in 100..199 || response.statusCode == 204)
    require(!bodyForbidden || wire.isEmpty())
    val header = buildString {
        append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        if (!http) append("CSeq: $cseq\r\n")
        append("Server: AirTunes/220.68\r\n")
        response.contentType?.let { append("Content-Type: $it\r\n") }
        response.headers.forEach { (key, value) -> append("$key: $value\r\n") }
        // RFC 9110 forbids Content-Length on 101 upgrades, unlike normal empty HTTP replies.
        if (!bodyForbidden && (wire.isNotEmpty() || http)) append("Content-Length: ${wire.size}\r\n")
        append("\r\n")
    }
    return header.toByteArray(Charsets.US_ASCII) + wire
}
