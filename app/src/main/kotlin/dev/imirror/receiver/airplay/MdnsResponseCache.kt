package dev.imirror.receiver.airplay

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Reuses records announced by Android NSD, including its real hostname and
 * collision-resolved instance names. Inventing a second SRV target for an NSD
 * instance would give clients conflicting answers for a unique DNS record.
 *
 * Feed only packets received from this device into [rememberLocalResponse].
 * All parsing is bounded, and no sockets or Android classes are needed here.
 */
internal class MdnsResponseCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Reply(val bytes: ByteArray, val unicast: Boolean)
    private data class Question(val name: List<String>, val type: Int, val clazz: Int)
    private data class Record(
        val name: List<String>, val type: Int, val ttl: Long,
        val bytes: ByteArray = byteArrayOf(), val target: List<String> = emptyList(),
        val cacheFlush: Boolean = false,
    ) {
        val key: String get() = "${name.key()}/$type/${bytes.joinToString(",")}/${target.key()}"
    }
    private data class Cached(val record: Record, val expiresAt: Long)
    private data class Packet(
        val id: Int, val flags: Int, val authorityCount: Int,
        val questions: List<Question>, val answers: List<Record>, val records: List<Record>,
    )
    private val records = linkedMapOf<String, Cached>()
    private var instanceNames = emptyList<List<String>>()

    @Synchronized
    fun activate(airPlayName: String, raopName: String) {
        instanceNames = listOf(listOf(airPlayName) + AIRPLAY, listOf(raopName) + RAOP)
    }

    @Synchronized
    fun rememberLocalResponse(data: ByteArray, length: Int) {
        val packet = parse(data, length) ?: return
        if (packet.flags and 0x8000 == 0) return
        val time = now()
        records.entries.removeAll { it.value.expiresAt <= time }
        val flushed = packet.records.filter { it.cacheFlush && it.ttl > 0 }.map { it.name.key() to it.type }.toSet()
        records.entries.removeAll { (it.value.record.name.key() to it.value.record.type) in flushed }
        packet.records.forEach { record ->
            if (record.ttl == 0L) records.remove(record.key)
            else records[record.key] = Cached(record, time + minOf(record.ttl, 120L) * 1000)
        }
        while (records.size > 128) records.remove(records.keys.first())
    }

    @Synchronized
    fun answer(data: ByteArray, length: Int, legacy: Boolean): Reply? {
        val packet = parse(data, length) ?: return null
        // Let NSD alone handle conflict probes and truncated multi-packet queries.
        if (packet.flags and 0xFA0F != 0 || packet.authorityCount != 0 || instanceNames.isEmpty()) return null
        val time = now()
        val live = records.values.filter { it.expiresAt > time + 1000 }
            .map { it.record.copy(ttl = minOf(it.record.ttl, (it.expiresAt - time) / 1000)) }
        val serviceRecords = live.filter { record ->
            instanceNames.any { it.sameName(record.name) } ||
                (record.type == 12 && instanceNames.any { it.sameName(record.target) })
        }
        val hosts = serviceRecords.filter { it.type == 33 }.map { it.target }
        val ours = serviceRecords + live.filter { record ->
            record.type in listOf(1, 28) && hosts.any { it.sameName(record.name) }
        }
        val matched = packet.questions.filter { it.clazz and 0x7fff in listOf(1, 255) }
            .filter { q -> ours.any { it.name.sameName(q.name) && (q.type == 255 || it.type == q.type) } }
        if (matched.isEmpty()) return null
        val answers = ours.filter { record -> matched.any { q ->
            record.name.sameName(q.name) && (q.type == 255 || record.type == q.type)
        } }.filter { record -> legacy || packet.answers.none { it.key == record.key && it.ttl >= (record.ttl + 1) / 2 } }
        if (answers.isEmpty()) return null
        val extras = ours.filter { r -> answers.none { it.key == r.key } }
        val unicast = legacy || matched.all { it.clazz and 0x8000 != 0 }
        val out = ByteArrayOutputStream()
        out.u16(if (legacy) packet.id else 0)
        out.u16(0x8400)
        out.u16(if (legacy) packet.questions.size else 0)
        out.u16(answers.size)
        out.u16(0)
        out.u16(extras.size)
        if (legacy) packet.questions.forEach { q ->
            out.name(q.name); out.u16(q.type); out.u16(q.clazz and 0x7fff)
        }
        (answers + extras).forEach { record ->
            out.name(record.name)
            out.u16(record.type)
            out.u16(if (!legacy && record.type != 12) 0x8001 else 1)
            out.u32(if (legacy) minOf(record.ttl, 10) else record.ttl)
            val payload = ByteArrayOutputStream().apply {
                write(record.bytes)
                if (record.target.isNotEmpty()) name(record.target)
            }.toByteArray()
            out.u16(payload.size)
            out.write(payload)
        }
        return if (out.size() <= 9000) Reply(out.toByteArray(), unicast) else null
    }

    private fun parse(data: ByteArray, length: Int): Packet? {
        if (length !in 12..minOf(data.size, 9000)) return null
        val reader = Reader(data, length)
        return try {
            val id = reader.u16()
            val flags = reader.u16()
            if (flags and 0x780f != 0) return null
            val qd = reader.u16()
            val an = reader.u16()
            val ns = reader.u16()
            val ar = reader.u16()
            if (qd > 64 || an + ns + ar > 128) return null
            val questions = List(qd) { Question(reader.name(), reader.u16(), reader.u16()) }
            val answers = mutableListOf<Record>()
            val all = mutableListOf<Record>()
            repeat(an + ns + ar) { index ->
                val name = reader.name()
                val type = reader.u16()
                val rawClass = reader.u16()
                val clazz = rawClass and 0x7fff
                val ttl = reader.u32()
                val size = reader.u16()
                val end = reader.pos + size
                require(end <= length)
                val record = when (type) {
                    12 -> Record(name, type, ttl, target = reader.name())
                    33 -> Record(name, type, ttl, reader.bytes(6), reader.name())
                    1, 28, 16 -> Record(name, type, ttl, reader.bytes(size))
                    else -> null
                }
                if (record != null) {
                    require(reader.pos == end)
                    require(type != 1 || size == 4)
                    require(type != 28 || size == 16)
                    if (clazz == 1) {
                        all.add(record.copy(cacheFlush = rawClass and 0x8000 != 0))
                        if (index < an) answers.add(record)
                    }
                }
                reader.pos = end
            }
            Packet(id, flags, ns, questions, answers, all)
        } catch (_: IllegalArgumentException) { null }
    }

    private class Reader(val data: ByteArray, val length: Int) {
        var pos = 0
        fun u16(): Int {
            require(pos + 2 <= length)
            return ((data[pos++].toInt() and 255) shl 8) or (data[pos++].toInt() and 255)
        }
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun bytes(size: Int): ByteArray {
            require(size >= 0 && pos + size <= length)
            return data.copyOfRange(pos, pos + size).also { pos += size }
        }
        fun name(): List<String> {
            val labels = mutableListOf<String>()
            var cursor = pos
            var resume = -1
            var hops = 0
            var encodedSize = 1
            while (true) {
                require(cursor < length)
                val size = data[cursor].toInt() and 255
                if (size == 0) {
                    pos = if (resume >= 0) resume else cursor + 1
                    return labels
                }
                if (size and 0xc0 == 0xc0) {
                    require(cursor + 1 < length && ++hops <= 16)
                    val target = ((size and 63) shl 8) or (data[cursor + 1].toInt() and 255)
                    require(target < cursor)
                    if (resume < 0) resume = cursor + 2
                    cursor = target
                } else {
                    require(size in 1..63 && cursor + size + 1 <= length)
                    encodedSize += size + 1
                    require(encodedSize <= 255)
                    labels.add(String(data, cursor + 1, size, Charsets.UTF_8))
                    cursor += size + 1
                }
            }
        }
    }

    companion object {
        private val AIRPLAY = listOf("_airplay", "_tcp", "local")
        private val RAOP = listOf("_raop", "_tcp", "local")
        private fun List<String>.sameName(other: List<String>) = size == other.size &&
            indices.all { this[it].equals(other[it], ignoreCase = true) }
        private fun List<String>.key() = joinToString("/") { "${it.length}:${it.lowercase(Locale.ROOT)}" }
        private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8 and 255); write(v and 255) }
        private fun ByteArrayOutputStream.u32(v: Long) { u16((v shr 16).toInt()); u16(v.toInt()) }
        private fun ByteArrayOutputStream.name(labels: List<String>) {
            labels.forEach { label ->
                val bytes = label.toByteArray(Charsets.UTF_8)
                write(bytes.size); write(bytes)
            }
            write(0)
        }
    }
}
