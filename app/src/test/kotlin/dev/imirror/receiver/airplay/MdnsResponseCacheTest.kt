package dev.imirror.receiver.airplay

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MdnsResponseCacheTest {
    private val service = listOf("_airplay", "_tcp", "local")
    private val instance = listOf("Bedroom") + service
    private val host = listOf("Android-real-host", "local")
    private val announcement = packet(flags = 0x8400, records = listOf(
        record(service, 12, dnsName(instance)),
        record(instance, 33, byteArrayOf(0, 0, 0, 0, 0x1b, 0x58) + dnsName(host), clazz = 0x8001),
        record(instance, 16, byteArrayOf(9) + "model=TV1".toByteArray(), clazz = 0x8001),
        record(host, 1, byteArrayOf(192.toByte(), 168.toByte(), 1, 110), clazz = 0x8001),
    ))

    private fun cache(now: () -> Long = { 0L }) = MdnsResponseCache(now).also {
        it.rememberLocalResponse(announcement, announcement.size)
        it.activate("Bedroom", "021122334455@Bedroom")
    }

    @Test
    fun `browse returns exact local NSD identity and complete resolution bundle`() {
        val query = packet(questions = listOf(question(service)))
        val reply = cache().answer(query, query.size, legacy = false)!!
        assertFalse(reply.unicast)
        assertEquals(1, u16(reply.bytes, 6))
        assertEquals(3, u16(reply.bytes, 10))
        val text = reply.bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("Android-real-host"))
        assertTrue(text.contains("Bedroom"))
        assertFalse(text.contains("iMirror-"))
    }

    @Test
    fun `QU query gets unicast while normal Mac browse gets multicast`() {
        val qu = packet(questions = listOf(question(service, clazz = 0x8001)))
        assertTrue(cache().answer(qu, qu.size, false)!!.unicast)
        val qm = packet(questions = listOf(question(service)))
        assertFalse(cache().answer(qm, qm.size, false)!!.unicast)
    }

    @Test
    fun `legacy answer repeats transaction and questions and caps TTL without cache flush`() {
        val query = packet(id = 0x1234, questions = listOf(question(service)))
        val reply = cache().answer(query, query.size, true)!!
        assertTrue(reply.unicast)
        assertEquals(0x1234, u16(reply.bytes, 0))
        assertEquals(1, u16(reply.bytes, 4))
        var pos = 12 + question(service).size
        repeat(u16(reply.bytes, 6) + u16(reply.bytes, 10)) {
            while (reply.bytes[pos].toInt() != 0) pos += 1 + (reply.bytes[pos].toInt() and 255)
            pos++
            assertEquals(1, u16(reply.bytes, pos + 2))
            assertTrue(u16(reply.bytes, pos + 4) == 0 && u16(reply.bytes, pos + 6) <= 10)
            pos += 10 + u16(reply.bytes, pos + 8)
        }
    }

    @Test
    fun `compressed repeated questions are accepted without duplicate answers`() {
        val compressed = byteArrayOf(0xc0.toByte(), 12, 0, 12, 0, 1)
        val query = packet(questions = listOf(question(service), compressed))
        val reply = cache().answer(query, query.size, false)!!
        assertEquals(1, u16(reply.bytes, 6))
    }

    @Test
    fun `cache refuses malformed compressed names oversized lengths and huge counts`() {
        val cache = cache()
        val loop = packet(questions = listOf(byteArrayOf(0xc0.toByte(), 12, 0, 12, 0, 1)))
        assertNull(cache.answer(loop, loop.size, false))
        assertNull(cache.answer(ByteArray(12), 500, false))
        assertNull(cache.answer(byteArrayOf(0), 1, false))
        val query = packet(questions = listOf(question(service)))
        query[4] = 127
        assertNull(cache.answer(query, query.size, false))
        for (size in announcement.indices) {
            cache.rememberLocalResponse(announcement.copyOf(size), size)
        }
    }

    @Test
    fun `records expire and goodbye removes old receiver`() {
        var time = 0L
        val cache = cache { time }
        val query = packet(questions = listOf(question(service)))
        time = 121_000
        assertNull(cache.answer(query, query.size, false))
        time = 0
        cache.rememberLocalResponse(announcement, announcement.size)
        val goodbye = packet(flags = 0x8400, records = listOf(record(service, 12, dnsName(instance), ttl = 0)))
        cache.rememberLocalResponse(goodbye, goodbye.size)
        assertNull(cache.answer(query, query.size, false))
    }

    @Test
    fun `known answers suppress unnecessary repeat multicast responses`() {
        val query = packet(questions = listOf(question(service)),
            records = listOf(record(service, 12, dnsName(instance), ttl = 100)))
        assertNull(cache().answer(query, query.size, false))
    }

    @Test
    fun `cache never answers for other locally advertised services`() {
        val other = listOf("Unrelated") + service
        val otherPacket = packet(flags = 0x8400, records = listOf(record(service, 12, dnsName(other))))
        val cache = cache()
        cache.rememberLocalResponse(otherPacket, otherPacket.size)
        val query = packet(questions = listOf(question(service)))
        val reply = cache.answer(query, query.size, false)!!
        assertFalse(reply.bytes.toString(Charsets.ISO_8859_1).contains("Unrelated"))
    }

    @Test
    fun `receiver stays silent until names have been registered`() {
        val cache = MdnsResponseCache()
        cache.rememberLocalResponse(announcement, announcement.size)
        val query = packet(questions = listOf(question(service)))
        assertNull(cache.answer(query, query.size, false))
    }

    private fun packet(id: Int = 0, flags: Int = 0, questions: List<ByteArray> = emptyList(),
                       records: List<ByteArray> = emptyList()): ByteArray = ByteArrayOutputStream().apply {
        u16(id); u16(flags); u16(questions.size); u16(records.size); u16(0); u16(0)
        questions.forEach { write(it) }; records.forEach { write(it) }
    }.toByteArray()

    private fun question(name: List<String>, type: Int = 12, clazz: Int = 1) = ByteArrayOutputStream().apply {
        write(dnsName(name)); u16(type); u16(clazz)
    }.toByteArray()

    private fun record(name: List<String>, type: Int, data: ByteArray, ttl: Int = 120, clazz: Int = 1) =
        ByteArrayOutputStream().apply {
            write(dnsName(name)); u16(type); u16(clazz); u16(0); u16(ttl); u16(data.size); write(data)
        }.toByteArray()

    private fun dnsName(name: List<String>) = ByteArrayOutputStream().apply {
        name.forEach { write(it.toByteArray().size); write(it.toByteArray()) }; write(0)
    }.toByteArray()

    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8 and 255); write(v and 255) }
    private fun u16(data: ByteArray, pos: Int) = ((data[pos].toInt() and 255) shl 8) or (data[pos + 1].toInt() and 255)
}
