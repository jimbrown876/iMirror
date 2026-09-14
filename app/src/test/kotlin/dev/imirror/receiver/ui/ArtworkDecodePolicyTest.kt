package dev.imirror.receiver.ui

import org.junit.Assert.*
import org.junit.Test

class ArtworkDecodePolicyTest {
    @Test
    fun `large cover is sampled near the existing TCL card size without full resolution allocation`() {
        val sample = ArtworkDecodePolicy.sampleSize(4000, 4000, 479, 479)!!
        assertEquals(8, sample)
        assertEquals(500, 4000 / sample)
        assertTrue(500 * 500 * 4 <= ArtworkDecodePolicy.MAX_DECODED_BYTES)
    }

    @Test
    fun `panoramas tall covers and large display density remain within fixed pixel memory limits`() {
        for ((width, height) in listOf(16_000 to 100, 100 to 16_000, 8000 to 8000, 1025 to 1025)) {
            for (target in listOf(100, 479, 2000)) {
                val sample = ArtworkDecodePolicy.sampleSize(width, height, target, target)!!
                assertEquals("sample must be a power of two", 0, sample and (sample - 1))
                val decodedWidth = (width + sample - 1) / sample
                val decodedHeight = (height + sample - 1) / sample
                assertTrue(decodedWidth <= minOf(target * 2, ArtworkDecodePolicy.MAX_DECODED_EDGE))
                assertTrue(decodedHeight <= minOf(target * 2, ArtworkDecodePolicy.MAX_DECODED_EDGE))
                assertTrue(decodedWidth.toLong() * decodedHeight * 4 <= ArtworkDecodePolicy.MAX_DECODED_BYTES)
            }
        }
    }

    @Test
    fun `small covers are not upscaled during decode and invalid or extreme bounds are rejected`() {
        assertEquals(1, ArtworkDecodePolicy.sampleSize(100, 200, 479, 479))
        assertNull(ArtworkDecodePolicy.sampleSize(-1, 100, 479, 479))
        assertNull(ArtworkDecodePolicy.sampleSize(100, 0, 479, 479))
        assertNull(ArtworkDecodePolicy.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 479, 479))
        assertNull(ArtworkDecodePolicy.sampleSize(16_000, 16_000, 479, 479))
        assertNull(ArtworkDecodePolicy.sampleSize(100, 100, 0, 479))
    }

    @Test
    fun `metadata-only updates and duplicate cover bytes do not trigger another decode`() {
        val state = ArtworkRevision()
        val artwork = ByteArray(95_330) { it.toByte() }
        val revision = state.update(artwork)!!
        repeat(100) { assertNull(state.update(artwork)) }
        assertNull(state.update(artwork.copyOf()))
        assertTrue(state.isCurrent(revision))
    }

    @Test
    fun `track change and clear invalidate outstanding decode results and allow reloading`() {
        val state = ArtworkRevision()
        val first = state.update(byteArrayOf(1))!!
        val second = state.update(byteArrayOf(2))!!
        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
        state.clear()
        assertFalse(state.isCurrent(second))
        assertNotNull(state.update(byteArrayOf(2)))
        val empty = state.update(null)!!
        assertTrue(state.isCurrent(empty))
        assertNull(state.update(null))
    }
}
