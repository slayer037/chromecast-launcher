package com.majendra.tvlauncher.providers

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

data class GuideChannel(val id: String, val name: String, val number: String?, val image: String?)
/** Guide descriptions sometimes run a heading into the text ("2026The latest", "G1After", "Women'sCoverage"). */
object GuideText {
    private val joined = Regex("(?<=\\d)(?=[A-Z][a-z])|(?<=\\)|'s)(?=[A-Z])")
    fun description(value: String?) = value?.replace(joined, ". ")
}
data class GuideProgram(val channel: String, val title: String, val description: String?, val image: String?, val start: Long?, val end: Long?) {
    fun isNow(now: Long) = start != null && end != null && end > start && start <= now && now < end
    fun progress(now: Long): Float? = if (start != null && end != null && end > start) ((now - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f) else null
}
data class Guide(val channels: List<GuideChannel>, val programs: List<GuideProgram>, val overlaps: Int) {
    // Grouped once, in guide order: Now/Next used to scan every programme of every channel for each tile on each refresh.
    private val byChannel: Map<String, List<GuideProgram>> by lazy { programs.groupBy { it.channel } }
    fun programs(channel: String): List<GuideProgram> = byChannel[channel].orEmpty()
    fun current(channel: String, now: Long) = programs(channel).filter { it.isNow(now) }.maxByOrNull { it.start ?: Long.MIN_VALUE }
    fun next(channel: String, now: Long) = programs(channel).filter { it.start != null && it.end != null && it.end > it.start && it.start > now }.minByOrNull { it.start!! }
}

object XmlTvParser {
    private const val MAX_BYTES = 12L * 1024 * 1024
    private const val HEAD_CHARS = 4096
    private const val DOCTYPE = "<!DOCTYPE"
    private val xmltvDeclaration = Regex("""<!DOCTYPE\s+tv\s+SYSTEM\s+["']xmltv\.dtd["']\s*>""")
    private val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss xx").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    fun timestamp(value: String?): Long? = value?.let { runCatching { OffsetDateTime.parse(it.trim(), format).toInstant().toEpochMilli() }.getOrNull() }

    /**
     * Streams [input] through the parser: no copy of the document is held, only the parser's buffers and the result.
     * [keep] (channel id, start, stop) decides before a programme's text is collected, so a ten-megabyte guide can be
     * reduced to one day or a few channels without first building every programme.
     */
    fun parse(input: InputStream, keep: ((String, Long?, Long?) -> Boolean)? = null): Guide {
        // XMLTV.net declares a fixed external DTD. Strip only that declaration, never load it;
        // reject internal subsets and all other DTDs before the SAX parser can expand entities.
        // The text is decoded here as strict UTF-8, so the parser never chooses an encoding the DOCTYPE check cannot read.
        val bytes = Bounded(input)
        val guarded = GuardedText(InputStreamReader(bytes, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))
        guarded.begin()
        val channels = mutableListOf<GuideChannel>()
        val programs = mutableListOf<GuideProgram>()
        val ids = HashMap<String, String>() // One String per channel id rather than one per programme.
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        // Android XML parsers support different feature sets; block all external entity resolution too.
        for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")) runCatching { factory.setFeature(feature, false) }
        val reader = factory.newSAXParser().xmlReader
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", object : org.xml.sax.ext.DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DOCTYPE is not permitted") }
        })
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = object : DefaultHandler() {
            var channel: String? = null; var programChannel: String? = null; var skip = false
            var name = ""; var number: String? = null; var title = ""; var desc: String? = null
            var image: String? = null; var start: Long? = null; var end: Long? = null
            val text = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                text.setLength(0)
                when (qName) {
                    "channel" -> { channel = a.getValue("id")?.let { ids.getOrPut(it) { it } }; name = ""; number = null; image = null }
                    "programme" -> {
                        val id = a.getValue("channel")
                        title = ""; desc = null; image = null; start = timestamp(a.getValue("start")); end = timestamp(a.getValue("stop"))
                        skip = keep != null && (id == null || !keep(id, start, end))
                        programChannel = if (skip) null else id?.let { ids.getOrPut(it) { it } }
                    }
                    "icon" -> if (!skip && image == null) image = a.getValue("src")
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (!skip) text.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (skip) { if (qName == "programme") skip = false; return }
                val value = text.toString().trim()
                when (qName) {
                    "display-name" -> if (name.isEmpty()) name = value
                    "lcn" -> number = value
                    "title" -> if (title.isEmpty()) title = value
                    "desc" -> if (desc == null) desc = GuideText.description(value)
                    "channel" -> { channel?.let { channels.add(GuideChannel(it, name.ifEmpty { it }, number, image)) }; channel = null }
                    "programme" -> { programChannel?.let { if (title.isNotBlank()) programs.add(GuideProgram(it, title, desc, image, start, end)) }; programChannel = null }
                }
            }
        }
        try { reader.parse(InputSource(guarded)) }
        catch (error: Exception) {
            // The two guards can only stop a read with an IOException; report them as the parse failures they are.
            if (bytes.exceeded) throw SAXException("XML size limit")
            if (guarded.rejected) throw SAXException("Unsupported DOCTYPE")
            throw error
        }
        var overlaps = 0
        programs.groupBy { it.channel }.values.forEach { entries ->
            var lastEnd = Long.MIN_VALUE
            entries.filter { it.start != null && it.end != null && it.end > it.start }.sortedBy { it.start }.forEach { p ->
                if (p.start!! < lastEnd) overlaps++
                lastEnd = maxOf(lastEnd, p.end!!)
            }
        }
        return Guide(channels, programs, overlaps)
    }

    /** Bounds the bytes actually read, whatever length the source claimed. */
    private class Bounded(input: InputStream) : FilterInputStream(input) {
        var exceeded = false; private set
        private var total = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { if (it > 0) count(it) }
        private fun count(amount: Int) { total += amount; if (total > MAX_BYTES) { exceeded = true; throw IOException("XML size limit") } }
    }

    /**
     * The document text with the one XMLTV declaration removed from its head. Every character handed to the parser is
     * checked: any other DOCTYPE, wherever it appears, stops the read before the parser has seen the whole token.
     */
    private class GuardedText(private val source: Reader) : Reader() {
        var rejected = false; private set
        private var head = CharArray(0); private var position = 0; private var matched = 0
        fun begin() {
            val buffer = CharArray(HEAD_CHARS); var filled = 0
            while (filled < buffer.size) { val count = source.read(buffer, filled, buffer.size - filled); if (count < 0) break; filled += count }
            val safe = String(buffer, 0, filled).replace(xmltvDeclaration, "")
            if (safe.contains(DOCTYPE)) { rejected = true; throw SAXException("Unsupported DOCTYPE") }
            head = safe.toCharArray()
        }
        override fun read(target: CharArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = if (position < head.size) minOf(length, head.size - position).also { System.arraycopy(head, position, target, offset, it); position += it }
                else source.read(target, offset, length)
            for (index in offset until offset + count) {
                val char = target[index]
                matched = if (char == DOCTYPE[matched]) matched + 1 else if (char == '<') 1 else 0
                if (matched == DOCTYPE.length) { rejected = true; throw IOException("Unsupported DOCTYPE") }
            }
            return count
        }
        override fun close() = source.close()
    }
}
