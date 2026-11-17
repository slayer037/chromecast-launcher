package com.majendra.tvlauncher.providers.cinemeta

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Arrays
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream
import kotlin.math.roundToInt

/**
 * Every known public Cinemeta title of one type, held as parallel primitive columns: about 45 bytes a title, so
 * 40,000 titles cost roughly 3 MB of heap and a filter is one linear pass. Not thread-safe; the owner locks.
 * Only anonymous catalogue fields are stored: no account state, no search text, no descriptions.
 */
internal class CinemetaIndex(val type: String) {
    var size = 0
        private set
    var revision = 0L
        private set
    var dirty = false
    /** Keyed by [CatalogueStream.key]. */
    val cursors = LinkedHashMap<String, StreamCursor>()
    private var ids = LongArray(INITIAL)
    private var years = ShortArray(INITIAL)
    private var days = IntArray(INITIAL)
    private var ratings = ByteArray(INITIAL)
    private var runtimes = ShortArray(INITIAL)
    private var masks = IntArray(INITIAL)
    private var popularity = FloatArray(INITIAL)
    private var nameStart = IntArray(INITIAL)
    private var nameLength = ShortArray(INITIAL)
    private var names = ByteArray(INITIAL * 16)
    private var namesUsed = 0
    private val genreNames = ArrayList<String>()
    private var slots = IntArray(INITIAL * 2) // Open addressing by id; holds row + 1, 0 is empty.
    /** [ordered]: how many leading keys are in their final order. */
    private class Memo(val filter: CinemetaFilter, val excluded: LongArray, val revision: Long, val keys: LongArray, var ordered: Int)
    private var memo: Memo? = null

    fun cursor(stream: CatalogueStream) = cursors.getOrPut(stream.key) { StreamCursor() }
    fun genres(): List<String> = genreNames.toList()
    fun contains(id: String) = CinemetaIds.parse(id).let { it > 0 && find(it) >= 0 }

    /** Adds or updates one title. A value the source left out never replaces a known one. True when anything changed. */
    fun upsert(meta: CatalogueMeta): Boolean {
        if (meta.type != null && meta.type != type) return false
        val id = CinemetaIds.parse(meta.id)
        if (id <= 0) return false
        var row = find(id)
        var changed = false
        if (row < 0) { if (size >= MAX_TITLES) return false; row = append(id); changed = true }
        val name = meta.name.take(MAX_NAME).toByteArray(Charsets.UTF_8)
        if (!sameName(row, name)) {
            if (namesUsed + name.size > names.size) names = names.copyOf(maxOf(names.size + names.size / 2, namesUsed + name.size))
            System.arraycopy(name, 0, names, namesUsed, name.size)
            nameStart[row] = namesUsed; nameLength[row] = name.size.toShort(); namesUsed += name.size; changed = true
        }
        if (meta.year > 0 && years[row].toInt() != meta.year) { years[row] = meta.year.toShort(); changed = true }
        if (meta.releasedDay != CatalogueMeta.UNKNOWN_DAY && days[row] != meta.releasedDay) { days[row] = meta.releasedDay; changed = true }
        if (meta.rating in 1..100 && ratings[row].toInt() != meta.rating) { ratings[row] = meta.rating.toByte(); changed = true }
        if (meta.runtime in 1..1440 && runtimes[row].toInt() != meta.runtime) { runtimes[row] = meta.runtime.toShort(); changed = true }
        val mask = mask(meta.genres)
        if (mask != 0 && masks[row] != mask) { masks[row] = mask; changed = true }
        if (meta.popularity >= 0f && popularity[row] != meta.popularity) { popularity[row] = meta.popularity; changed = true }
        if (changed) touch()
        return changed
    }

    fun touch() { revision++; dirty = true; memo = null }

    fun count(filter: CinemetaFilter, excluded: LongArray = NONE) = keys(filter, excluded, 0).size

    /** One page of matches in the filter's order, and the number of matches indexed so far. */
    fun query(filter: CinemetaFilter, offset: Int, limit: Int, excluded: LongArray = NONE): Pair<List<CinemetaTitle>, Int> {
        val keys = keys(filter, excluded, (offset.coerceAtLeast(0).toLong() + limit.coerceAtLeast(0)).coerceAtMost(MAX_TITLES.toLong()).toInt())
        val from = offset.coerceIn(0, keys.size)
        val to = (from.toLong() + limit.coerceAtLeast(0)).coerceAtMost(keys.size.toLong()).toInt()
        return (from until to).map { title(keys[it].toInt()) } to keys.size
    }

    /** One match chosen with equal chance. */
    fun draw(filter: CinemetaFilter, excluded: LongArray, random: kotlin.random.Random): CinemetaTitle? {
        val keys = keys(filter, excluded, 0)
        return if (keys.isEmpty()) null else title(keys[random.nextInt(keys.size)].toInt())
    }

    /**
     * Match keys: inverted sort value in the high half, row in the low half, so ascending keys are the display order.
     * Only the first [needed] are put in order (a first page selects its sixty from forty thousand without sorting
     * the rest); a later, deeper page finishes the sort. The last answer is kept for paging.
     */
    private fun keys(filter: CinemetaFilter, excluded: LongArray, needed: Int): LongArray {
        memo?.takeIf { it.revision == revision && it.filter == filter && it.excluded.contentEquals(excluded) }?.let {
            if (it.ordered < minOf(needed, it.keys.size)) { it.keys.sort(); it.ordered = it.keys.size }
            return it.keys
        }
        var required = 0
        var possible = filter.type == type
        for (genre in filter.genres) { val bit = genreNames.indexOf(genre); if (bit < 0) possible = false else required = required or (1 shl bit) }
        val bounded = filter.yearFrom != null || filter.yearTo != null
        val from = filter.yearFrom ?: Int.MIN_VALUE
        val to = filter.yearTo ?: Int.MAX_VALUE
        val rating = ((filter.minRating?.takeIf { it.isFinite() } ?: 0.0) * 10).roundToInt().coerceIn(0, 101)
        val skipped = if (excluded.isEmpty()) null else BooleanArray(size).also { for (id in excluded) { val row = find(id); if (row >= 0) it[row] = true } }
        var found = LongArray(256)
        var count = 0
        if (possible) for (row in 0 until size) {
            if ((masks[row] and required) != required || ratings[row] < rating) continue
            if (bounded) { val year = years[row].toInt(); if (year == 0 || year < from || year > to) continue }
            if (skipped != null && skipped[row]) continue
            if (count == found.size) found = found.copyOf(count * 2)
            found[count++] = ((Int.MAX_VALUE - sortValue(row, filter.sort)).toLong() shl 32) or row.toLong()
        }
        val keys = found.copyOf(count)
        val ordered = if (needed >= count / 8 || !select(keys, needed)) { keys.sort(); count } else { Arrays.sort(keys, 0, needed); needed }
        memo = Memo(filter, excluded, revision, keys, ordered)
        return keys
    }

    /** Moves the [count] smallest keys to the front, in any order. False when the pivots degenerate and the caller should sort. */
    private fun select(keys: LongArray, count: Int): Boolean {
        if (count <= 0) return true
        var low = 0
        var high = keys.size - 1
        var rounds = 0
        while (low < high) {
            if (++rounds > 64) return false
            val a = keys[low]; val b = keys[(low + high) ushr 1]; val c = keys[high]
            val pivot = if (a < b) (if (b < c) b else if (a < c) c else a) else (if (a < c) a else if (b < c) c else b)
            var left = low
            var right = high
            while (left <= right) {
                while (keys[left] < pivot) left++
                while (keys[right] > pivot) right--
                if (left <= right) { val moved = keys[left]; keys[left] = keys[right]; keys[right] = moved; left++; right-- }
            }
            if (count - 1 <= right) high = right else if (count - 1 >= left) low = left else return true
        }
        return true
    }

    /** 0..Int.MAX_VALUE, larger first. Positive floats order the same way as their bit patterns. */
    private fun sortValue(row: Int, sort: CinemetaSort): Int {
        val popular = popularity[row].let { if (it > 0f) java.lang.Float.floatToRawIntBits(it) else 0 }
        return when (sort) {
            CinemetaSort.POPULARITY -> popular
            CinemetaSort.RATING -> (ratings[row].toInt() shl 24) or (popular ushr 7)
            CinemetaSort.NEWEST -> {
                val year = years[row].toInt()
                val day = if (days[row] != CatalogueMeta.UNKNOWN_DAY) days[row] else if (year > 0) (year - 1970) * 1461 / 4 else -DAY_OFFSET
                ((day + DAY_OFFSET).coerceIn(0, 0x1FFFF) shl 14) or (popular ushr 17)
            }
        }
    }

    private fun title(row: Int) = CinemetaTitle(CinemetaIds.format(ids[row]), type,
        String(names, nameStart[row], nameLength[row].toInt(), Charsets.UTF_8), years[row].toInt().takeIf { it > 0 },
        ratings[row].toInt().takeIf { it > 0 }?.let { it / 10.0 }, runtimes[row].toInt().takeIf { it > 0 },
        genreNames.filterIndexed { bit, _ -> masks[row] and (1 shl bit) != 0 })

    private fun mask(genres: List<String>): Int {
        var mask = 0
        for (genre in genres) {
            var bit = genreNames.indexOf(genre)
            if (bit < 0 && genreNames.size < 32) { genreNames += genre; bit = genreNames.size - 1; dirty = true }
            if (bit >= 0) mask = mask or (1 shl bit)
        }
        return mask
    }

    private fun sameName(row: Int, name: ByteArray): Boolean {
        if (nameLength[row].toInt() != name.size) return false
        val start = nameStart[row]
        for (index in name.indices) if (names[start + index] != name[index]) return false
        return true
    }

    private fun find(id: Long): Int {
        val mask = slots.size - 1
        var slot = hash(id) and mask
        while (true) {
            val row = slots[slot] - 1
            if (row < 0) return -1
            if (ids[row] == id) return row
            slot = (slot + 1) and mask
        }
    }
    private fun hash(id: Long): Int { val mixed = id * -0x61c8864680b583ebL; return (mixed ushr 32).toInt() xor mixed.toInt() }
    private fun place(row: Int) {
        val mask = slots.size - 1
        var slot = hash(ids[row]) and mask
        while (slots[slot] != 0) slot = (slot + 1) and mask
        slots[slot] = row + 1
    }
    private fun append(id: Long): Int {
        if (size == ids.size) grow(size + size / 2)
        val row = size++
        ids[row] = id; years[row] = 0; days[row] = CatalogueMeta.UNKNOWN_DAY; ratings[row] = 0; runtimes[row] = 0
        masks[row] = 0; popularity[row] = -1f; nameStart[row] = 0; nameLength[row] = 0
        if (size * 5 > slots.size * 3) { slots = IntArray(slots.size * 2); for (existing in 0 until size) place(existing) } else place(row)
        return row
    }
    private fun grow(capacity: Int) {
        ids = ids.copyOf(capacity); years = years.copyOf(capacity); days = days.copyOf(capacity); ratings = ratings.copyOf(capacity)
        runtimes = runtimes.copyOf(capacity); masks = masks.copyOf(capacity); popularity = popularity.copyOf(capacity)
        nameStart = nameStart.copyOf(capacity); nameLength = nameLength.copyOf(capacity)
    }

    /** Bytes held by the columns, for sizing checks. */
    fun heapBytes(): Long = ids.size * 8L + years.size * 2L + days.size * 4L + ratings.size + runtimes.size * 2L + masks.size * 4L +
        popularity.size * 4L + nameStart.size * 4L + nameLength.size * 2L + names.size + slots.size * 4L + (memo?.keys?.size ?: 0) * 8L

    fun write(out: DataOutput) {
        out.writeUTF(type)
        out.writeInt(genreNames.size); genreNames.forEach(out::writeUTF)
        out.writeInt(size); out.writeInt(namesUsed)
        out.write(ByteBuffer.allocate(size * 8).apply { asLongBuffer().put(ids, 0, size) }.array())
        out.write(ByteBuffer.allocate(size * 2).apply { asShortBuffer().put(years, 0, size) }.array())
        out.write(ByteBuffer.allocate(size * 4).apply { asIntBuffer().put(days, 0, size) }.array())
        out.write(ratings, 0, size)
        out.write(ByteBuffer.allocate(size * 2).apply { asShortBuffer().put(runtimes, 0, size) }.array())
        out.write(ByteBuffer.allocate(size * 4).apply { asIntBuffer().put(masks, 0, size) }.array())
        out.write(ByteBuffer.allocate(size * 4).apply { asFloatBuffer().put(popularity, 0, size) }.array())
        out.write(ByteBuffer.allocate(size * 4).apply { asIntBuffer().put(nameStart, 0, size) }.array())
        out.write(ByteBuffer.allocate(size * 2).apply { asShortBuffer().put(nameLength, 0, size) }.array())
        out.write(names, 0, namesUsed)
        out.writeInt(cursors.size)
        for ((key, cursor) in cursors) { out.writeUTF(key); out.writeInt(cursor.nextSkip); out.writeBoolean(cursor.complete); out.writeLong(cursor.startedAt); out.writeLong(cursor.headAt) }
    }

    companion object {
        const val MAX_TITLES = 120_000
        private const val INITIAL = 1024
        private const val MAX_NAME = 200
        private const val MAX_NAME_BYTES = 48 * 1024 * 1024
        private const val DAY_OFFSET = 40_000
        val NONE = LongArray(0)

        /** Throws on anything inconsistent; the caller treats that as a corrupt file. */
        fun read(input: DataInput, type: String): CinemetaIndex {
            val index = CinemetaIndex(type)
            require(input.readUTF() == type)
            val genres = input.readInt(); require(genres in 0..32)
            repeat(genres) { index.genreNames += input.readUTF().also { require(it.length in 1..80) } }
            require(index.genreNames.toSet().size == genres)
            val size = input.readInt(); require(size in 0..MAX_TITLES)
            val used = input.readInt(); require(used in 0..MAX_NAME_BYTES)
            fun bytes(count: Int) = ByteBuffer.wrap(ByteArray(count).also(input::readFully))
            val capacity = maxOf(size, INITIAL)
            index.grow(capacity)
            bytes(size * 8).asLongBuffer().get(index.ids, 0, size)
            bytes(size * 2).asShortBuffer().get(index.years, 0, size)
            bytes(size * 4).asIntBuffer().get(index.days, 0, size)
            input.readFully(index.ratings, 0, size)
            bytes(size * 2).asShortBuffer().get(index.runtimes, 0, size)
            bytes(size * 4).asIntBuffer().get(index.masks, 0, size)
            bytes(size * 4).asFloatBuffer().get(index.popularity, 0, size)
            bytes(size * 4).asIntBuffer().get(index.nameStart, 0, size)
            bytes(size * 2).asShortBuffer().get(index.nameLength, 0, size)
            index.names = ByteArray(maxOf(used, INITIAL * 16)); input.readFully(index.names, 0, used); index.namesUsed = used
            index.size = size
            var capacitySlots = INITIAL * 2
            while (size * 5 > capacitySlots * 3) capacitySlots *= 2
            index.slots = IntArray(capacitySlots)
            for (row in 0 until size) {
                require(index.ids[row] > 0 && index.find(index.ids[row]) < 0)
                require(index.nameLength[row] >= 0 && index.nameStart[row] >= 0 && index.nameStart[row] + index.nameLength[row] <= used)
                require(index.ratings[row] in 0..100)
                index.place(row)
            }
            val cursors = input.readInt(); require(cursors in 0..2048)
            repeat(cursors) {
                val key = input.readUTF(); require(key.length <= 120)
                val cursor = StreamCursor(input.readInt(), input.readBoolean(), input.readLong(), input.readLong())
                require(cursor.nextSkip >= 0 && cursor.nextSkip % CatalogueStream.PAGE == 0)
                index.cursors[key] = cursor
            }
            return index
        }
    }
}

/**
 * The index on disk: magic, version, payload, CRC-32 of the payload. Saves go to a temporary file that replaces the
 * old one in a single rename, so a crash leaves either the old index or the new one. A file that fails any check is
 * deleted and an empty index returned; the catalogue pages are simply read again when a filter next needs them.
 */
internal class CinemetaIndexFile(private val directory: File) {
    private fun file(type: String) = File(directory, "$type.idx")

    fun load(type: String): CinemetaIndex {
        val file = file(type)
        if (!file.isFile) return CinemetaIndex(type)
        return try {
            if (file.length() !in 24..MAX_FILE) throw IOException("Index size out of range")
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes, 0, 8)
            if (header.int != MAGIC || header.int != VERSION) throw IOException("Unknown index format")
            val crc = CRC32().apply { update(bytes, 8, bytes.size - 16) }
            if (ByteBuffer.wrap(bytes, bytes.size - 8, 8).long != crc.value) throw IOException("Index checksum mismatch")
            val payload = ByteArrayInputStream(bytes, 8, bytes.size - 16)
            CinemetaIndex.read(DataInputStream(payload), type).also { if (payload.available() != 0) throw IOException("Trailing index data") }
        } catch (_: IOException) { file.delete(); CinemetaIndex(type) }
        catch (_: RuntimeException) { file.delete(); CinemetaIndex(type) }
    }

    /** False when the device could not store it; the in-memory index stays usable and dirty. */
    fun save(index: CinemetaIndex): Boolean = try {
        directory.mkdirs()
        val target = file(index.type)
        val temporary = File(directory, "${index.type}.idx.tmp")
        FileOutputStream(temporary).use { stream ->
            val buffered = BufferedOutputStream(stream, 1 shl 16)
            DataOutputStream(buffered).apply { writeInt(MAGIC); writeInt(VERSION); flush() }
            val crc = CRC32()
            DataOutputStream(CheckedOutputStream(buffered, crc)).apply { index.write(this); flush() }
            DataOutputStream(buffered).apply { writeLong(crc.value); flush() }
            stream.fd.sync()
        }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        index.dirty = false
        true
    } catch (_: IOException) { false }

    private companion object {
        const val MAGIC = 0x434D5831 // "CMX1"
        const val VERSION = 1
        const val MAX_FILE = 64L * 1024 * 1024
    }
}
