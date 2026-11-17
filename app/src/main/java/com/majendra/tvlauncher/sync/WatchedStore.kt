package com.majendra.tvlauncher.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.majendra.tvlauncher.data.ContentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal data class WatchedBatch(val revision: Long, val imports: Map<String, WatchedEntry>,
    val operations: List<WatchedOperation>, val catalog: Map<String, WatchedEntry>)

/**
 * One durable local cache for watched and episode choices. No network.
 *
 * Constructing it is free. The file is read on first use, and sealed and written by one background thread:
 * [lock] only ever guards memory, so the UI thread never waits behind encryption or the disk. Only
 * [prepare] and [prepareWatching] wait for disk; callers must keep these legacy helpers off the UI thread.
 */
internal class WatchedStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    /** Serialises the file. Never requested while holding [lock]. */
    private val io = Any()
    private val file by lazy { AtomicFile(File(app.noBackupFilesDir, "watched-sync.enc")) }
    /** The last file in the released keystore-sealed format, kept from the moment it is first replaced. */
    private val legacyCopy get() = File(file.baseFile.path + ".v1")
    private val envelope = WatchedEnvelope({ keyStore().getKey(KEY_ALIAS, null) as? SecretKey }, ::createKey)
    @Volatile private var ready = false
    private var readable = true
    private var legacy = false
    private var persistError = false
    /** The current file would not open and the older copy was loaded instead. Guarded by [io]. */
    private var unreadableCurrent = false
    private var state = WatchedState()
    private var revision = 0L // Every change to [state].
    private var required = 0L // The newest revision that has to reach the disk.
    private var durable = 0L  // The newest revision known to be on the disk.
    private var queued = false
    private var lastWriteAt = 0L
    private var seen: Set<String>? = null
    private var effectiveWatching: WatchingSnapshot? = null
    private val events = MutableStateFlow(0L)
    val changes: StateFlow<Long> = events.asStateFlow()

    private inline fun <T> locked(action: () -> T): T { load(); return synchronized(lock) { action() } }
    private fun healthy() = readable && !persistError

    /** Computed once per change: a render asks several times for every title it shows. */
    fun seenKeys(): Set<String> = locked { seen ?: WatchedPolicy.effective(state).values.filter { it.seen }.map { it.key }.toSet().also { seen = it } }
    fun isSeen(key: String) = key in seenKeys()
    fun knownKidsKeys(): Set<String> = locked { state.kidsKeys.filter(WatchedPolicy::isKidsKey).toSet() }
    fun watching(): WatchingSnapshot = locked {
        effectiveWatching ?: WatchingPolicy.effective(state.watching, state.watchingPending).also { effectiveWatching = it }
    }
    fun setWatching(item: ContentItem, value: Boolean): Boolean = locked {
        val key = WatchingActions.key(item) ?: return@locked false
        val metadata = WatchedPolicy.metadata(item) ?: return@locked false
        if (metadata.title == null) return@locked false
        val current = watching().entries.firstOrNull { it.key == key }
        if (current?.watching == value) return@locked healthy()
        appendWatching(WatchingOperation(UUID.randomUUID().toString(), key, watching = value,
            title = metadata.title, year = metadata.year, poster = metadata.poster))
    }
    fun setEpisode(key: String, next: EpisodeProgress?): Boolean = locked {
        if (watching().entries.none { it.key == key }) return@locked false
        appendWatching(WatchingOperation(UUID.randomUUID().toString(), key, progressSet = true, progress = next))
    }
    fun advanceEpisode(key: String): Boolean = locked {
        val current = watching().entries.firstOrNull { it.key == key && it.watching } ?: return@locked false
        val next = current.progress?.afterWatched() ?: return@locked false
        appendWatching(WatchingOperation(UUID.randomUUID().toString(), key, progressSet = true, progress = next))
    }
    fun useStremioProgress(key: String): Boolean = locked {
        if (watching().entries.none { it.key == key && it.watching }) return@locked false
        appendWatching(WatchingOperation(UUID.randomUUID().toString(), key, progressMode = "stremio"))
    }
    private fun appendWatching(operation: WatchingOperation): Boolean {
        if (state.watchingPending.size >= WatchingPolicy.MAX_PENDING) return false
        val before = watching()
        if (before.entries.none { it.key == operation.key } && before.entries.size >= WatchingPolicy.MAX_ENTRIES) return false
        val next = state.copy(watchingPending = state.watchingPending + operation)
        val changed = WatchingPolicy.active(before) != WatchingPolicy.active(WatchingPolicy.effective(next.watching, next.watchingPending))
        change(next, changed)
        // The write itself follows on the store's own thread; a store that cannot save says so here.
        return healthy()
    }
    fun importMovieHistory(items: List<ContentItem>) = locked {
        val next = WatchedPolicy.importMovieHistory(state, items)
        val added = next.imports.size - state.imports.size
        change(next, added > 0)
        added
    }
    fun seed(source: String, keys: Set<String>, kids: Boolean = false) = locked {
        change(WatchedPolicy.seed(state, source, keys, kids), true)
    }
    fun set(key: String, seen: Boolean, metadata: WatchedEntry? = null): Boolean = locked {
        if (!WatchedPolicy.validKey(key) || state.pending.size >= WatchedPolicy.MAX_PENDING) return@locked false
        val effective = WatchedPolicy.effective(state)
        if (effective[key]?.seen == seen && key in effective) return@locked true
        if (key !in effective && effective.size >= WatchedPolicy.MAX_ENTRIES) return@locked false
        val base = metadata ?: state.catalog[key] ?: effective[key] ?: WatchedEntry(key, seen)
        val entry = base.copy(key = key, seen = seen, revision = 0, kids = WatchedPolicy.isKidsKey(key))
        val op = WatchedOperation(UUID.randomUUID().toString(), entry)
        change(state.copy(pending = state.pending + op,
            kidsKeys = (state.kidsKeys.filter(WatchedPolicy::isKidsKey) + if (entry.kids) setOf(key) else emptySet()).take(WatchedPolicy.MAX_ENTRIES).toSet()), true)
        true
    }
    fun setMany(keys: Set<String>, seen: Boolean): Boolean = locked {
        val effective = WatchedPolicy.effective(state)
        val changed = keys.filter { WatchedPolicy.validKey(it) && effective[it]?.seen != seen }
        if (state.pending.size + changed.size > WatchedPolicy.MAX_PENDING) return@locked false
        val operations = changed.map { key -> WatchedOperation(UUID.randomUUID().toString(),
            (state.catalog[key] ?: effective[key] ?: WatchedEntry(key, seen)).copy(seen = seen, revision = 0, kids = WatchedPolicy.isKidsKey(key))) }
        if (operations.isNotEmpty()) change(state.copy(pending = state.pending + operations), true)
        true
    }
    /**
     * Metadata for locally selected titles, not titles that merely scrolled past.
     * Entries already recorded are kept. The catalogue is only copied when a title is new or has changed.
     */
    fun catalog(items: List<ContentItem>) = locked {
        var catalog: MutableMap<String, WatchedEntry>? = null
        var pending: MutableSet<String>? = null
        val kids = state.kidsKeys.filter(WatchedPolicy::isKidsKey).toMutableSet()
        for (item in items) {
            val value = WatchedPolicy.metadata(item) ?: continue
            if (value.title == null) continue
            val known = catalog ?: state.catalog
            if (known.size >= WatchedPolicy.MAX_ENTRIES && value.key !in known) continue
            val next = value.copy(kids = WatchedPolicy.isKidsKey(value.key))
            if (known[value.key] != next) {
                (catalog ?: state.catalog.toMutableMap().also { catalog = it })[value.key] = next
                (pending ?: state.catalogPending.toMutableSet().also { pending = it }).add(value.key)
            }
            if (value.kids && kids.size < WatchedPolicy.MAX_ENTRIES) kids += value.key
        }
        change(state.copy(catalog = catalog ?: state.catalog, catalogPending = pending ?: state.catalogPending, kidsKeys = kids), false)
    }
    /** Persistence must succeed before a request can leave the device. May wait for the disk: not for the UI thread. */
    fun prepare(): WatchedBatch? {
        load()
        repeat(3) {
            synchronized(lock) {
                if (!readable) return null
                if (durable >= required) return WatchedBatch(state.revision, state.imports.entries.take(500).associate { it.toPair() }, state.pending.take(200),
                    state.catalogPending.take(500).mapNotNull { state.catalog[it]?.let { value -> it to value } }.toMap())
            }
            if (!synchronized(io) { persist() }) return null
        }
        return null
    }
    fun prepareWatching(): List<WatchingOperation>? {
        load()
        repeat(3) {
            synchronized(lock) {
                if (!readable) return null
                if (durable >= required) return state.watchingPending.take(200)
            }
            if (!synchronized(io) { persist() }) return null
        }
        return null
    }
    fun accept(batch: WatchedBatch, reply: WatchedReply, now: Long) = locked {
        require(reply.acknowledged.all { id -> batch.operations.any { it.id == id } })
        val oldSeen = WatchedPolicy.effective(state).filterValues { it.seen }.keys
        val next = WatchedPolicy.merge(state, reply, batch.imports.keys, batch.catalog, now)
        change(next, oldSeen != WatchedPolicy.effective(next).filterValues { it.seen }.keys)
    }
    fun acceptWatching(reply: WatchingSnapshot, now: Long) = locked {
        val next = WatchingPolicy.accept(state.watching, reply, now)
        val changed = WatchingPolicy.active(watching()) != WatchingPolicy.active(WatchingPolicy.effective(next, state.watchingPending))
        change(state.copy(watching = next), changed)
    }
    fun acceptWatching(sentIds: Set<String>, reply: WatchingExchange, now: Long) = locked {
        val before = WatchingPolicy.active(watching())
        val merged = WatchingPolicy.merge(state.watching, state.watchingPending, reply, sentIds, now)
        val next = state.copy(watching = merged.snapshot, watchingPending = merged.pending)
        change(next, before != WatchingPolicy.active(WatchingPolicy.effective(next.watching, next.watchingPending)))
    }
    fun info(): WatchedStoreInfo = locked {
        val seen = WatchedPolicy.effective(state).values.filter { it.seen }
        WatchedStoreInfo(state.revision, state.lastSuccessMillis, seen.size,
            state.pending.size + state.imports.size + state.watchingPending.size, healthy(),
            seen.count { !WatchedPolicy.isKidsKey(it.key) }, seen.count { WatchedPolicy.isKidsKey(it.key) })
    }
    fun notifyUi() = synchronized(lock) { events.value = events.value + 1 }

    /** Call with [lock] held. Memory changes now; the file follows on the writer thread. */
    private fun change(next: WatchedState, notify: Boolean) {
        if (next == state) return
        // Every successful poll moves the sync stamps. They are not worth sealing the whole history again.
        val stampsOnly = WatchedPersistPolicy.timestampsOnly(state, next)
        if (!stampsOnly) seen = null
        effectiveWatching = null
        state = next
        revision++
        if (!stampsOnly) required = revision
        if (!stampsOnly || WatchedPersistPolicy.flushTimestamps(lastWriteAt, System.currentTimeMillis())) schedule()
        if (notify) events.value = events.value + 1
    }
    private fun schedule() {
        if (!readable || queued) return
        queued = true
        writer.execute { synchronized(io) { synchronized(lock) { queued = false }; persist() } }
    }
    private fun load() {
        if (ready) return
        synchronized(io) {
            if (ready) return
            val loaded = read()
            synchronized(lock) {
                state = loaded
                lastWriteAt = System.currentTimeMillis()
                ready = true
                // Leave the keystore-sealed format behind as soon as it has been read once.
                if (legacy) schedule()
            }
        }
    }
    /** Call with [io] held. */
    private fun read(): WatchedState {
        val present = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
        if (present) try { return open(file.readFully()) } catch (_: Exception) { }
        // The copy kept at migration is older, but an older history is better than none.
        val copy = legacyCopy
        if (copy.exists()) try { return open(copy.readBytes()).also { unreadableCurrent = present } } catch (_: Exception) { }
        if (present || copy.exists()) readable = false
        return WatchedState()
    }
    private fun open(bytes: ByteArray): WatchedState {
        require(bytes.size in 32..MAX_FILE_BYTES)
        val opened = envelope.open(bytes)
        return WatchedCodec.decode(opened.plain).also { legacy = opened.legacy }
    }
    /** Call with [io] held and [lock] free: sealing and the disk never block a reader. */
    private fun persist(): Boolean {
        val (snapshot, target) = synchronized(lock) { if (!readable) return false; state to revision }
        val saved = try {
            val copy = legacyCopy
            if (legacy && file.baseFile.exists() && !copy.exists()) keep(file.baseFile, copy)
            val sealed = envelope.seal(WatchedCodec.encode(snapshot))
            // Never write what the next start would refuse to read.
            require(sealed.size in 32..MAX_FILE_BYTES)
            // A file that failed to open may only have failed this once (a keystore hiccup), and it is newer than the
            // copy that was loaded: set it aside under its own name instead of writing over it.
            if (unreadableCurrent) { setAside(file.baseFile); unreadableCurrent = false }
            val stream = file.startWrite()
            try { stream.write(sealed); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            legacy = false
            true
        } catch (_: Exception) { false }
        synchronized(lock) {
            if (saved) { durable = maxOf(durable, target); lastWriteAt = System.currentTimeMillis() }
            persistError = !saved
        }
        return saved
    }
    /** The file and anything AtomicFile may restore in its place (its backup and its unfinished write) all move aside together. */
    private fun setAside(current: File) {
        val stamp = System.currentTimeMillis()
        for (suffix in listOf("", ".bak", ".new")) {
            val source = File(current.path + suffix)
            if (source.exists() && !source.renameTo(File(current.path + ".unreadable-" + stamp + suffix)))
                throw IOException("Cannot set aside the unreadable watched store")
        }
    }
    private fun keep(source: File, target: File) {
        val temp = File(target.path + ".tmp")
        FileOutputStream(temp).use { out -> source.inputStream().use { it.copyTo(out) }; out.fd.sync() }
        if (!temp.renameTo(target)) throw IOException("Cannot keep the previous watched store")
    }
    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
    }.generateKey()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    companion object {
        private const val KEY_ALIAS = "publiclauncher.watched.local.v1"
        /** One limit for reading and writing, so a file this store wrote can always be opened again. */
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
        private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "watched-store").apply { isDaemon = true } }
        @Volatile private var instance: WatchedStore? = null
        fun shared(context: Context): WatchedStore = instance ?: synchronized(this) {
            instance ?: WatchedStore(context).also { instance = it }
        }
    }
}
internal data class WatchedStoreInfo(val revision: Long, val lastSuccessMillis: Long, val seenCount: Int, val pending: Int, val durable: Boolean,
    val adultSeenCount: Int, val kidsSeenCount: Int)
