package com.majendra.tvlauncher.diagnostics

import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * events.json stays one valid JSON array for the export scripts, but an event now costs a small append
 * instead of reading, parsing and rewriting the whole file. Every [compactAt] entries the file is cut
 * back to the newest [keep]. One instance per file, used from one thread.
 */
internal class EventJournal(private val file: File, private val keep: Int = 150, private val compactAt: Int = 300) {
    private var count = -1 // Unknown until the existing file has been normalised.

    fun append(entry: String) {
        try {
            if (count < 0 || count >= compactAt) compact()
            RandomAccessFile(file, "rw").use { out ->
                // The normalised file always ends "\n]": step back over it and close the array again.
                out.seek(out.length() - TAIL.size)
                out.write(((if (count == 0) "" else ",\n") + entry).toByteArray(Charsets.UTF_8))
                out.write(TAIL)
            }
            count++
        } catch (_: IOException) { count = -1 } // Re-read and repair on the next event.
    }

    /** Also the migration from the older indented file, and the repair for a file cut short by a power loss. */
    private fun compact() {
        val old = try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
        val entries = (0 until old.length()).mapNotNull { old.optJSONObject(it)?.toString() }.takeLast(keep - 1)
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { out ->
            out.write("[\n".toByteArray(Charsets.UTF_8))
            out.write(entries.joinToString(",\n").toByteArray(Charsets.UTF_8))
            out.write(TAIL)
        }
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        count = entries.size
    }

    private companion object { val TAIL = "\n]".toByteArray(Charsets.UTF_8) }
}
