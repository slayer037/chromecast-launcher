package com.majendra.tvlauncher.providers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Derived public metadata only. Raw XML identity invalidates this disposable cache. */
internal object KayoCompactGuide {
    const val MAX_BYTES = 1024 * 1024
    private const val MAGIC = 0x4b41594f
    private const val SCHEMA = 1
    private val ids = KayoGuide.channels.map { it.guideId }.toSet()

    /** For [XmlTvParser.parse]: the national guide's other channels are dropped as they are read, not after. */
    val wanted: (String, Long?, Long?) -> Boolean = { channel, _, _ -> channel in ids }

    fun select(guide: Guide): Guide = Guide(
        guide.channels.filter { it.id in ids }, guide.programs.filter { it.channel in ids }, 0
    )

    fun encode(guide: Guide, xmlModified: Long, xmlSize: Long): ByteArray {
        val selected = select(guide)
        val bytes = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                if (size() >= MAX_BYTES) throw IOException("Compact guide size limit")
                super.write(value)
            }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                if (length > MAX_BYTES - size()) throw IOException("Compact guide size limit")
                super.write(value, offset, length)
            }
        }
        DataOutputStream(bytes).use { out ->
            fun text(value: String?) { out.writeBoolean(value != null); if (value != null) out.writeUTF(value) }
            fun time(value: Long?) { out.writeBoolean(value != null); if (value != null) out.writeLong(value) }
            out.writeInt(MAGIC); out.writeInt(SCHEMA)
            out.writeLong(xmlModified); out.writeLong(xmlSize)
            out.writeInt(selected.channels.size)
            selected.channels.forEach { channel ->
                out.writeUTF(channel.id); out.writeUTF(channel.name); text(channel.number); text(channel.image)
            }
            out.writeInt(selected.programs.size)
            selected.programs.forEach { programme ->
                out.writeUTF(programme.channel); out.writeUTF(programme.title)
                text(programme.description); text(programme.image); time(programme.start); time(programme.end)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray, xmlModified: Long, xmlSize: Long): Guide {
        if (bytes.size > MAX_BYTES) throw IOException("Compact guide size limit")
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun text(): String? = if (input.readBoolean()) input.readUTF() else null
            fun time(): Long? = if (input.readBoolean()) input.readLong() else null
            fun count(max: Int): Int = input.readInt().also {
                if (it !in 0..max) throw IOException("Invalid compact guide count")
            }
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA ||
                input.readLong() != xmlModified || input.readLong() != xmlSize)
                throw IOException("Compact guide identity changed")
            val channels = List(count(ids.size)) {
                GuideChannel(input.readUTF(), input.readUTF(), text(), text())
            }
            val programmes = List(count(10_000)) {
                GuideProgram(input.readUTF(), input.readUTF(), GuideText.description(text()), text(), time(), time())
            }
            if (input.available() != 0 || channels.any { it.id !in ids } || programmes.any { it.channel !in ids })
                throw IOException("Invalid compact guide records")
            Guide(channels, programmes, 0)
        }
    }
}
