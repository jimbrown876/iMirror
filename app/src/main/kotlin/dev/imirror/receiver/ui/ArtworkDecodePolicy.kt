package dev.imirror.receiver.ui

/** Pure limits for network album covers; compressed byte size alone does not bound bitmap memory. */
internal object ArtworkDecodePolicy {
    const val MAX_DECODED_EDGE = 1024
    const val MAX_DECODED_BYTES = MAX_DECODED_EDGE * MAX_DECODED_EDGE * 4
    private const val MAX_SOURCE_EDGE = 16_384
    private const val MAX_SOURCE_PIXELS = 64_000_000L

    /** Powers of two match BitmapFactory's sampling rules. Reject invalid/extreme image bounds. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int? {
        if (width !in 1..MAX_SOURCE_EDGE || height !in 1..MAX_SOURCE_EDGE ||
            width.toLong() * height > MAX_SOURCE_PIXELS || targetWidth <= 0 || targetHeight <= 0) return null
        // Keep at most twice the card resolution for sharp rendering, with a fixed TV memory cap.
        val maxWidth = minOf(targetWidth.toLong() * 2, MAX_DECODED_EDGE.toLong())
        val maxHeight = minOf(targetHeight.toLong() * 2, MAX_DECODED_EDGE.toLong())
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > maxWidth ||
            (height.toLong() + sample - 1) / sample > maxHeight) sample *= 2
        return sample
    }
}

/** Keeps metadata-only updates from decoding the same cover and invalidates asynchronous results. */
internal class ArtworkRevision {
    private var bytes: ByteArray? = null
    private var initialized = false
    private var revision = 0L

    fun update(next: ByteArray?): Long? {
        if (initialized && (bytes === next || (bytes != null && next != null && bytes!!.contentEquals(next)))) return null
        initialized = true
        bytes = next
        return ++revision
    }

    fun isCurrent(candidate: Long): Boolean = initialized && candidate == revision

    fun clear() {
        bytes = null
        initialized = false
        revision++
    }
}
