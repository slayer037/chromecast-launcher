package com.majendra.tvlauncher.providers

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * How much of a saved guide stays in memory. Nothing on screen looks further than 24 hours ahead (Sports Centre's
 * "today" runs to midnight), so 36 hours kept and a fresh window every 12 always covers it.
 */
internal object GuideWindow {
    private const val HOUR = 60 * 60_000L
    const val AHEAD = 36 * HOUR
    const val RENEW = 12 * HOUR
    /** Programmes still running at [from] or starting within the window; ones without a valid interval can never be shown. */
    fun keep(from: Long): (String, Long?, Long?) -> Boolean = { _, start, end ->
        start != null && end != null && end > start && end > from && start < from + AHEAD
    }
    fun covers(from: Long, now: Long) = now >= from && now - from < RENEW
}

/** Derived public schedule data only: one guide window, so a restart or a memory trim does not parse the XML again. */
internal object GuideWindowCodec {
    const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAGIC = 0x47574e44
    private const val SCHEMA = 1
    private const val MAX_CHANNELS = 2_000
    private const val MAX_PROGRAMMES = 60_000
    private const val MAX_TEXT = 64 * 1024
    private const val NO_TIME = Long.MIN_VALUE
    class Saved(val xmlModified: Long, val xmlSize: Long, val from: Long, val guide: Guide)

    fun write(output: OutputStream, saved: Saved) {
        val guide = saved.guide
        if (guide.channels.size > MAX_CHANNELS || guide.programs.size > MAX_PROGRAMMES) throw IOException("Guide window size limit")
        val out = DataOutputStream(output)
        fun text(value: String?) {
            if (value == null) { out.writeInt(-1); return }
            val encoded = value.toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_TEXT) throw IOException("Guide window text limit")
            out.writeInt(encoded.size); out.write(encoded)
        }
        val index = HashMap<String, Int>()
        guide.channels.forEachIndexed { position, channel -> index.putIfAbsent(channel.id, position) }
        out.writeInt(MAGIC); out.writeInt(SCHEMA)
        out.writeLong(saved.xmlModified); out.writeLong(saved.xmlSize); out.writeLong(saved.from); out.writeInt(guide.overlaps)
        out.writeInt(guide.channels.size)
        guide.channels.forEach { text(it.id); text(it.name); text(it.number); text(it.image) }
        out.writeInt(guide.programs.size)
        guide.programs.forEach { programme ->
            // Channel ids repeat on every programme: store the channel's position, or the id itself if it was never declared.
            val channel = index[programme.channel] ?: -1
            out.writeInt(channel); if (channel < 0) text(programme.channel)
            text(programme.title); text(programme.description); text(programme.image)
            out.writeLong(programme.start ?: NO_TIME); out.writeLong(programme.end ?: NO_TIME)
            if (out.size() > MAX_BYTES) throw IOException("Guide window size limit")
        }
        out.flush()
    }

    fun read(input: InputStream): Saved {
        val data = DataInputStream(BufferedInputStream(KayoGuide.LimitedInputStream(input, MAX_BYTES)))
        fun optional(): String? {
            val size = data.readInt()
            if (size == -1) return null
            if (size !in 0..MAX_TEXT) throw IOException("Invalid guide window text")
            return String(ByteArray(size).also { data.readFully(it) }, Charsets.UTF_8)
        }
        fun text(): String = optional() ?: throw IOException("Invalid guide window text")
        fun count(max: Int): Int = data.readInt().also { if (it !in 0..max) throw IOException("Invalid guide window count") }
        fun time(): Long? = data.readLong().takeIf { it != NO_TIME }
        if (data.readInt() != MAGIC || data.readInt() != SCHEMA) throw IOException("Unsupported guide window")
        val xmlModified = data.readLong(); val xmlSize = data.readLong(); val from = data.readLong(); val overlaps = data.readInt()
        val channels = List(count(MAX_CHANNELS)) { GuideChannel(text(), text(), optional(), optional()) }
        val programmes = List(count(MAX_PROGRAMMES)) {
            val position = data.readInt()
            val channel = if (position < 0) text() else channels.getOrNull(position)?.id ?: throw IOException("Invalid guide window channel")
            GuideProgram(channel, text(), optional(), optional(), time(), time())
        }
        if (data.read() != -1) throw IOException("Invalid guide window length")
        return Saved(xmlModified, xmlSize, from, Guide(channels, programmes, overlaps))
    }
}

/**
 * The one in-memory copy of a saved XMLTV file, limited to [GuideWindow] and shared by every reader. Before this,
 * Live TV and Sports Centre each parsed the whole file and kept every programme. All methods block: call off the main thread.
 */
internal class GuideStore(private val xml: File, private val compact: File) {
    private class Loaded(val modified: Long, val size: Long, val from: Long, val guide: Guide?)
    @Volatile private var memory: Loaded? = null

    /** The guide around [now], or null when no saved guide has schedules for it. An unusable file is not parsed again until it changes. */
    @Synchronized fun load(now: Long): Guide? {
        if (!xml.isFile) { memory = null; return null }
        val modified = xml.lastModified(); val size = xml.length()
        fun current(value: Loaded?) = value?.takeIf { it.modified == modified && it.size == size && GuideWindow.covers(it.from, now) }
        current(memory)?.let { return it.guide }
        val saved = runCatching { compact.inputStream().use(GuideWindowCodec::read) }.getOrNull()
        current(saved?.let { Loaded(it.xmlModified, it.xmlSize, it.from, usable(it.guide)) })?.let { memory = it; return it.guide }
        val parsed = runCatching { xml.inputStream().use { XmlTvParser.parse(it, GuideWindow.keep(now)) } }.getOrNull()?.let(::usable)
        return Loaded(modified, size, now, parsed).also { memory = it; save(it) }.guide
    }

    /** Adopts a finished download: parsed and checked before it replaces the saved file, then kept as the shared copy. */
    fun install(downloaded: File, now: Long): Guide {
        // Parsed before taking the lock: readers keep being served the current copy meanwhile.
        val parsed = downloaded.inputStream().use { XmlTvParser.parse(it, GuideWindow.keep(now)) }
        if (usable(parsed) == null) throw IOException("Guide contains no schedules")
        synchronized(this) {
            if (!replace(downloaded, xml)) throw IOException("Cannot persist guide")
            Loaded(xml.lastModified(), xml.length(), now, parsed).also { memory = it; save(it) }
        }
        return parsed
    }

    /** Drops the parsed copy without waiting for a reader; the next [load] restores it from the compact file. */
    fun release() { memory = null }

    private fun usable(guide: Guide) = guide.takeIf { it.channels.isNotEmpty() && it.programs.isNotEmpty() }

    private fun save(value: Loaded) {
        val guide = value.guide ?: return
        val temp = File(compact.path + ".tmp")
        // A failed write only costs the next start a parse: the XML remains the source.
        runCatching {
            temp.outputStream().buffered().use { GuideWindowCodec.write(it, GuideWindowCodec.Saved(value.modified, value.size, value.from, guide)) }
            replace(temp, compact)
        }
        temp.delete()
    }

    private fun replace(from: File, to: File) = try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); true
    } catch (_: IOException) { false }

    companion object {
        @Volatile private var melbourne: GuideStore? = null
        /** Live TV downloads this guide; the broadcaster rows and Sports Centre only read it. */
        fun melbourne(context: Context): GuideStore = melbourne ?: synchronized(this) {
            melbourne ?: GuideStore(File(context.filesDir, "melbourne.xml"), File(context.filesDir, "melbourne-window.bin")).also { melbourne = it }
        }
        /** For memory trims: a store that was never opened stays unopened. */
        fun releaseShared() { melbourne?.release() }
    }
}
