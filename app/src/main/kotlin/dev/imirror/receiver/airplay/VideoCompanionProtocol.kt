package dev.imirror.receiver.airplay

import dev.imirror.receiver.airplay.handshake.PlistCodec

/** Only URL-video commands belong on authenticated companion HTTP sockets, never RTSP media setup. */
internal fun isVideoCompanionRequest(request: RtspRequest): Boolean {
    if (request.protocol != "HTTP/1.1") return false
    val path = request.uri.substringBefore('?')
    return when (request.method) {
        "GET" -> path in setOf("/server-info", "/playback-info", "/scrub")
        "POST" -> path in setOf("/play", "/rate", "/scrub", "/stop", "/reverse", "/fp-setup", "/fp-setup2")
        "PUT" -> path == "/setProperty"
        else -> false
    }
}

/** Safari initializes these inactive/default properties before /play; no media mutation is needed. */
internal fun isDefaultVideoProperty(name: String, value: Any?): Boolean = when (name) {
    "forwardEndTime", "reverseEndTime" -> (value as? Map<*, *>)?.let {
        (it["value"] as? Number)?.toDouble() == 0.0 && (it["flags"] as? Number)?.toDouble() == 0.0
    } == true
    "actionAtItemEnd" -> (value as? Number)?.toDouble() == 0.0
    "selectedMediaArray" -> value is List<*> && value.isEmpty()
    else -> false
}

internal fun videoSessionId(request: RtspRequest): String? =
    request.headers["X-Apple-Session-ID"]?.takeIf {
        it.length in 1..128 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in "-_." }
    }

internal fun isVideoReverseUpgrade(request: RtspRequest): Boolean =
    request.method == "POST" && request.uri == "/reverse" && request.protocol == "HTTP/1.1" &&
        request.bodyBytes.isEmpty() && videoSessionId(request) != null &&
        request.headers["Upgrade"].equals("PTTH/1.0", ignoreCase = true) &&
        request.headers["Connection"]?.split(',')?.any { it.trim().equals("Upgrade", true) } == true &&
        request.headers["X-Apple-Purpose"].equals("event", ignoreCase = true)

/** No stopped event before /play: that would cancel an otherwise valid newly selected route. */
internal fun videoEventState(requested: Boolean, info: PlaybackInfo?): String? = when {
    !requested -> null
    info == null -> "stopped"
    !info.readyToPlay -> "loading"
    info.rate > 0 -> "playing"
    else -> "paused"
}

internal fun videoEventWire(sessionId: String, state: String): ByteArray {
    require(sessionId.length in 1..128 && sessionId.all { it.code < 128 && (it.isLetterOrDigit() || it in "-_.") })
    require(state in setOf("loading", "playing", "paused", "stopped"))
    val body = PlistCodec.encodeXml(mapOf("category" to "video", "sessionID" to 1L, "state" to state))
    val header = "POST /event HTTP/1.1\r\nContent-Type: application/x-apple-plist\r\n" +
        "Content-Length: ${body.size}\r\nX-Apple-Session-ID: $sessionId\r\n\r\n"
    return header.toByteArray(Charsets.US_ASCII) + body
}
