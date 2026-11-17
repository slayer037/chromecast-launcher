package com.majendra.tvlauncher.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.FeedResult
import java.io.*
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.majendra.tvlauncher.sync.WatchedPolicy
import com.majendra.tvlauncher.sync.WatchedStore

/** Local watched choices, with no per-child profile or playback history. */
class KidsWatchedStore(context: Context) {
    private val store = synchronized(lock) {
        shared ?: Store(File(context.applicationContext.noBackupFilesDir, "kids-watched.enc")).also { shared = it }
    }
    private val watched = WatchedStore.shared(context)
    init { watched.seed("kids-scope-v2", synchronized(lock) { store.keys }.mapNotNull(WatchedPolicy::legacyKidsKey).toSet(), kids = true) }
    fun canMarkWatched(item: ContentItem) = KidsWatchedPolicy.key(item) != null
    fun isWatched(item: ContentItem): Boolean = WatchedPolicy.key(item)?.let(watched::isSeen) == true
    fun markWatched(item: ContentItem) {
        val key = WatchedPolicy.key(item)?.takeIf { canMarkWatched(item) } ?: return
        watched.set(key, true, WatchedPolicy.metadata(item))
    }
    fun restore(item: ContentItem) { val key = WatchedPolicy.key(item) ?: return; watched.set(key, false, WatchedPolicy.metadata(item)) }
    fun clear() { watched.setMany(watched.seenKeys().intersect(watched.knownKidsKeys()), false) }
    fun count(): Int = watched.seenKeys().intersect(watched.knownKidsKeys()).size
    fun apply(result: FeedResult): FeedResult {
        watched.catalog(result.rows.flatMap { it.items })
        val seen = watched.seenKeys()
        return result.copy(rows = result.rows.map { row -> row.copy(items = row.items.filter { WatchedPolicy.key(it) !in seen },
            emptyMessage = row.emptyMessage ?: "No unwatched G-rated titles are available in this row.") })
    }
    private fun update(change: (Set<String>) -> Set<String>) = synchronized(lock) {
        val next = change(store.keys)
        if (next != store.keys) { store.keys = next; writer.execute { store.save(next) } }
    }
    private class Store(path: File) {
        private val file = AtomicFile(path)
        var keys: Set<String> = read()
        private fun read(): Set<String> = try {
            if (!file.baseFile.exists() || file.baseFile.length() !in 32..1_048_576) emptySet() else {
                val input = DataInputStream(ByteArrayInputStream(file.readFully()))
                require(input.readInt() == 1 && input.readInt() == 12)
                val iv = ByteArray(12).also { input.readFully(it) }
                val key = keyStore().getKey(alias, null) as? SecretKey
                if (key == null) emptySet() else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    KidsWatchedPolicy.decode(cipher.doFinal(input.readBytes()))
                }
            }
        } catch (_: Exception) { emptySet() }
        fun save(keys: Set<String>) {
            try {
                val key = (keyStore().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build())
                }.generateKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { out -> out.writeInt(1); out.writeInt(cipher.iv.size); out.write(cipher.iv)
                    out.write(cipher.doFinal(KidsWatchedPolicy.encode(keys))) }
                val stream = file.startWrite()
                try { stream.write(bytes.toByteArray()); file.finishWrite(stream) }
                catch (e: Exception) { file.failWrite(stream); throw e }
            } catch (_: Exception) { /* Keep memory only if secure persistence is unavailable. */ }
        }
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    companion object {
        private val lock = Any()
        private var shared: Store? = null
        private const val alias = "publiclauncher.kids.watched.v1"
        private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "kids-watched").apply { isDaemon = true } }
    }
}

internal object KidsWatchedPolicy {
    private val imdb = Regex("tt[0-9]{5,12}")
    fun key(item: ContentItem): String? {
        if (item.source != "kids" || item.metadata["type"] in setOf("channel", "tv") || item.isLive) return null
        val group = item.metadata["kidsGroupId"]?.takeIf { it.isNotBlank() }
            ?: item.metadata["stremioId"]?.takeIf { imdb.matches(it) }?.let { "imdb:$it" }
            ?: return null
        return group.takeIf { valid(it) }
    }
    private fun valid(key: String) = key.isNotBlank() && key.length <= 512 && key.none { it.isISOControl() }
    fun apply(result: FeedResult, hidden: Set<String>): FeedResult = result.copy(rows = result.rows.map { row ->
        row.copy(items = row.items.filter { key(it) !in hidden }, emptyMessage = row.emptyMessage
            ?: "No unwatched G-rated titles are available in this row.")
    })
    fun encode(keys: Set<String>): ByteArray {
        require(keys.size <= 1024 && keys.all(::valid))
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeInt(keys.size); keys.sorted().forEach(out::writeUTF)
        } }.toByteArray()
    }
    fun decode(bytes: ByteArray): Set<String> {
        val input = DataInputStream(ByteArrayInputStream(bytes)); require(input.readInt() == 1)
        val count = input.readInt(); require(count in 0..1024)
        val keys = List(count) { input.readUTF().also { require(valid(it)) } }.toSet()
        require(input.available() == 0); return keys
    }
}
