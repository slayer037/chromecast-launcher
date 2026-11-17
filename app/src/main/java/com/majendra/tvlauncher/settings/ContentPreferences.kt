package com.majendra.tvlauncher.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.data.FeedResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.majendra.tvlauncher.sync.WatchedPolicy
import com.majendra.tvlauncher.sync.WatchedStore
import com.majendra.tvlauncher.sync.WatchingPolicy

/** Presentation choices and watched titles remain in encrypted local storage. */
class ContentPreferences(context: Context) {
    private val store = synchronized(lock) {
        shared ?: Store(File(context.applicationContext.noBackupFilesDir, "content-preferences.enc")).also { shared = it }
    }
    private val watched = WatchedStore.shared(context)
    private val history = com.majendra.tvlauncher.sync.StremioHistorySource.shared(context)
    init { watched.seed("titles", synchronized(lock) { store.state.hiddenTitles }) }

    /** Raw personal membership stays excluded from fresh picks even when its display row is hidden. */
    fun watchTonightExcludedKeys(): Set<String> {
        val notInterested = synchronized(lock) { store.state.notInterested }
        return history.cachedContinueMovies().orEmpty().mapNotNull(ContentPreferencePolicy::titleKey).toSet() +
            WatchingPolicy.active(watched.watching()).map { it.key } + notInterested
    }

    /** The account's resume row, when Movies is showing personal rows and the history reader holds one. */
    fun continueMovies(service: String, includePersonalRows: Boolean): List<ContentItem>? =
        if (service == "movies" && includePersonalRows) history.cachedContinueMovies() else null

    fun apply(service: String, result: FeedResult, includePersonalRows: Boolean = true,
        currentContinue: List<ContentItem>? = continueMovies(service, includePersonalRows)): FeedResult {
        // Projection can wait for the local watched store; never hold the focus-preference lock while it does.
        val snapshot = synchronized(lock) { store.state }
        val current = if (currentContinue == null) result else result.copy(rows =
            listOfNotNull(ContentRow("movies-continue-watching", "Continue watching", currentContinue).takeIf { currentContinue.isNotEmpty() }) +
                result.rows.filterNot { it.id == "movies-continue-watching" })
        val visible = ContentPreferencePolicy.apply(service, current, snapshot.copy(hiddenTitles = watched.seenKeys().filter {
            it.startsWith("movie:") || it.startsWith("series:")
        }.toSet()), includePersonalRows)
        val personal = if (includePersonalRows) WatchingPolicy.prepend(service, visible, watched.watching()) else visible
        val ordered = ContentPreferencePolicy.orderPersonalRows(service, personal, includePersonalRows)
        return ContentPreferencePolicy.decorateStars(service, ContentPreferencePolicy.filterNotInterested(service, ordered, snapshot), snapshot)
    }
    fun canFavorite(item: ContentItem) = ContentPreferencePolicy.channelKey(item) != null
    fun isFavorite(item: ContentItem): Boolean = synchronized(lock) {
        ContentPreferencePolicy.channelKey(item)?.let { it in store.state.favorites } == true
    }
    fun toggleFavorite(item: ContentItem) {
        val key = ContentPreferencePolicy.channelKey(item) ?: return
        update { state -> state.copy(favorites = state.favorites.toMutableSet().apply {
            if (!remove(key) && size < 256) add(key)
        }) }
    }
    fun setRowVisible(service: String, rowId: String, visible: Boolean) {
        if (!ContentPreferencePolicy.valid(service, rowId)) return
        val key = ContentPreferencePolicy.rowKey(service, rowId)
        update { state -> state.copy(hiddenRows = state.hiddenRows.toMutableSet().apply {
            if (visible) remove(key) else if (size < 256) add(key)
        }) }
    }
    fun isRowVisible(service: String, rowId: String): Boolean = synchronized(lock) {
        ContentPreferencePolicy.rowKey(service, rowId) !in store.state.hiddenRows
    }
    /** The same film sits in several genre rows, so the row it was focused in is remembered with it. */
    fun rememberFocus(service: String, item: ContentItem, row: String? = null) {
        if (!ContentPreferencePolicy.valid(service, item.id)) return
        val value = ContentPreferencePolicy.focusValue(row, item.id).takeIf { ContentPreferencePolicy.valid(service, it) } ?: item.id
        update(batched = true) { state -> if (state.focus[service] == value) state else state.copy(focus = state.focus + (service to value)) }
    }
    fun lastFocused(service: String): String? = synchronized(lock) { store.state.focus[service] }?.let(ContentPreferencePolicy::focusItem)
    /** Null for a focus saved before rows were recorded; the item is then found in the first row holding it. */
    fun lastFocusedRow(service: String): String? = synchronized(lock) { store.state.focus[service] }?.let(ContentPreferencePolicy::focusRow)
    fun canStar(item: ContentItem) = ContentPreferencePolicy.titleKey(item) != null
    fun isStarred(item: ContentItem): Boolean = synchronized(lock) {
        store.state.starredTitles.any { ContentPreferencePolicy.savedKey(it) == ContentPreferencePolicy.titleKey(item) }
    }
    fun toggleStarred(item: ContentItem): Boolean {
        val changed = synchronized(lock) {
            val next = ContentPreferencePolicy.toggleStarred(store.state, item)
            if (next == store.state) return@synchronized false
            update { next }
            true
        }
        // The local catalogue names selected titles, not every title that was drawn.
        if (changed && isStarred(item)) watched.catalog(listOf(item))
        return changed
    }
    fun starredFeed(): FeedResult = synchronized(lock) {
        ContentPreferencePolicy.decorateStars("starred", ContentPreferencePolicy.starredFeed(store.state), store.state)
    }
    /** Local dismissal is independent of local Seen, Watching and Starred membership. */
    fun canDismiss(item: ContentItem) = ContentPreferencePolicy.titleKey(item) != null
    fun isNotInterested(item: ContentItem): Boolean = synchronized(lock) {
        ContentPreferencePolicy.titleKey(item) in store.state.notInterested
    }
    fun toggleNotInterested(item: ContentItem) {
        update { ContentPreferencePolicy.toggleNotInterested(it, item) }
        if (isNotInterested(item)) watched.catalog(listOf(item))
    }
    fun notInterestedCount(type: String? = null): Int = synchronized(lock) {
        store.state.notInterested.count { type == null || it.startsWith("$type:") }
    }
    fun restoreNotInterested(type: String? = null) { update { ContentPreferencePolicy.restoreNotInterested(it, type) } }
    fun enrichStarredMetadata(item: ContentItem) { update { ContentPreferencePolicy.enrichStarredMetadata(it, item) } }
    fun starredItemsForEnrichment(): List<ContentItem> {
        val snapshot = synchronized(lock) { store.state }
        return ContentPreferencePolicy.starredFeed(snapshot.copy(starredGenre = null, starredSort = "recent")).rows.flatMap { it.items }
    }
    fun showPreferencesDialog(context: Context, onChanged: () -> Unit) {
        ContentPreferencesDialog.show(context, synchronized(lock) { store.state },
            { sort, genre -> update { it.copy(starredSort = sort, starredGenre = genre) }; onChanged() },
            { type -> restoreNotInterested(type); onChanged() })
    }
    /** Title keys ("movie:tt…") a catalogue query leaves out: Not interested always, Seen when [hideSeen]. Applied to answers, never stored with them. */
    fun excludedTitles(type: String, hideSeen: Boolean): Set<String> {
        val dismissed = synchronized(lock) { store.state.notInterested }
        return (if (hideSeen) dismissed + watched.seenKeys() else dismissed).filterTo(HashSet()) { it.startsWith("$type:") }
    }
    /** The filter last used in Movies or TV shows, in the form the screen encoded it. */
    fun savedFilter(service: String): String? = synchronized(lock) { store.state.filters[service] }
    fun saveFilter(service: String, value: String) {
        if (ContentPreferencePolicy.validFilter(service, value)) update { it.copy(filters = it.filters + (service to value)) }
    }
    /** Seen titles use the encrypted local watched store. */
    fun canHide(item: ContentItem) = ContentPreferencePolicy.titleKey(item) != null
    fun isHidden(item: ContentItem): Boolean = ContentPreferencePolicy.titleKey(item)?.let(watched::isSeen) == true
    /** One watched snapshot per render avoids rebuilding the full state for every catalogue item. */
    fun hiddenTitlePredicate(): (ContentItem) -> Boolean {
        val seen = watched.seenKeys()
        return { item -> ContentPreferencePolicy.titleKey(item)?.let(seen::contains) == true }
    }
    fun toggleHidden(item: ContentItem) {
        val key = ContentPreferencePolicy.titleKey(item) ?: return
        watched.set(key, !watched.isSeen(key), WatchedPolicy.metadata(item))
        if (watched.isSeen(key)) watched.catalog(listOf(item))
        update { state -> state.copy(savedShows = state.savedShows.filterNot { ContentPreferencePolicy.savedKey(it) == key }) }
    }
    fun hiddenCount(type: String): Int = watched.seenKeys().count { it.startsWith("$type:") }
    fun restoreHidden(type: String) { watched.setMany(watched.seenKeys().filter { it.startsWith("$type:") }.toSet(), false) }
    /** TV shows being watched, kept at the start of TV shows. */
    fun canSave(item: ContentItem) = ContentPreferencePolicy.titleKey(item)?.startsWith("series:") == true
    fun isSaved(item: ContentItem): Boolean = synchronized(lock) { store.state.savedShows.any { ContentPreferencePolicy.savedKey(it) == ContentPreferencePolicy.titleKey(item) } }
    fun toggleSaved(item: ContentItem) {
        val key = ContentPreferencePolicy.titleKey(item)?.takeIf { canSave(item) } ?: return
        val adding = !isSaved(item)
        update { state ->
            if (state.savedShows.any { ContentPreferencePolicy.savedKey(it) == key }) state.copy(savedShows = state.savedShows.filterNot { ContentPreferencePolicy.savedKey(it) == key })
            else state.copy(savedShows = (listOf(ContentPreferencePolicy.saved(item)) + state.savedShows).take(ContentPreferencePolicy.MAX_SAVED),
                hiddenTitles = state.hiddenTitles - key)
        }
        if (adding) { watched.set(key, false, WatchedPolicy.metadata(item)); watched.catalog(listOf(item)) }
    }

    private fun update(batched: Boolean = false, change: (ContentPreferenceState) -> ContentPreferenceState) = synchronized(lock) {
        val next = change(store.state)
        if (next != store.state) {
            store.state = next
            // Focus is remembered on every D-pad press; batch bursts into one encrypted write.
            // Favourites and row choices save straight away.
            if (!batched) writer.execute { store.save(synchronized(lock) { store.state }) }
            else if (!savePending) {
                savePending = true
                writer.schedule({ store.save(synchronized(lock) { savePending = false; store.state }) }, 500, TimeUnit.MILLISECONDS)
            }
        }
    }

    private class Store(path: File) {
        private val file = AtomicFile(path)
        var state = read()
        private fun read(): ContentPreferenceState {
            if (!file.baseFile.exists() || file.baseFile.length() !in 32..4_194_304) return ContentPreferenceState()
            return try {
                val input = DataInputStream(ByteArrayInputStream(file.readFully()))
                if (input.readInt() != 1 || input.readInt() != 12) return ContentPreferenceState()
                val iv = ByteArray(12).also { input.readFully(it) }
                val key = keyStore().getKey(alias, null) as? SecretKey ?: return ContentPreferenceState()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                ContentPreferencePolicy.decode(cipher.doFinal(input.readBytes()))
            } catch (_: Exception) { ContentPreferenceState() }
        }
        fun save(snapshot: ContentPreferenceState) {
            try {
                val key = (keyStore().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                ).apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build())
                }.generateKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { out ->
                    out.writeInt(1); out.writeInt(cipher.iv.size); out.write(cipher.iv)
                    out.write(cipher.doFinal(ContentPreferencePolicy.encode(snapshot)))
                }
                val stream = file.startWrite()
                try { stream.write(bytes.toByteArray()); file.finishWrite(stream) }
                catch (error: Exception) { file.failWrite(stream); throw error }
            } catch (_: Exception) { /* Keep the in-memory choice; never fall back to plaintext. */ }
        }
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    companion object {
        private val lock = Any()
        private var shared: Store? = null
        private var savePending = false
        private val writer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "content-preferences").apply { isDaemon = true } }
        private const val alias = "publiclauncher.content.preferences.v1"
    }
}

internal data class ContentPreferenceState(
    val favorites: Set<String> = emptySet(), val hiddenRows: Set<String> = emptySet(),
    val focus: Map<String, String> = emptyMap(), val hiddenTitles: Set<String> = emptySet(),
    val savedShows: List<String> = emptyList(), val starredTitles: List<String> = emptyList(),
    val notInterested: Set<String> = emptySet(), val starredSort: String = "recent", val starredGenre: String? = null,
    val starredMetadata: Map<String, StarredMetadata> = emptyMap(),
    /** Last used Movies and TV shows filter, by section. */
    val filters: Map<String, String> = emptyMap()
)

internal data class StarredMetadata(val rating: Double? = null, val genres: List<String> = emptyList())

internal object ContentPreferencePolicy {
    private val services = setOf("youtube", "stremio", "kayo", "kids", "movies", "series", "starred", "live", "tcl", "catchup", "7plus", "9now", "abc", "sbs", "10")
    private val channelServices = services - setOf("youtube", "stremio", "tcl", "catchup", "kids", "movies", "series", "starred")
    const val MAX_HIDDEN = 5000
    const val MAX_SAVED = 100
    private const val FIELD = '\u0001'
    private const val FOCUS_ROW = '\u0002'
    // Compiled once: titleKey runs several times for every title on every render.
    private val imdb = Regex("tt[0-9]{1,12}")
    private val titleKeyPattern = Regex("(?:movie|series):tt[0-9]{1,12}")
    fun valid(service: String, value: String) = service in services && value.isNotBlank() && value.length <= 2048
    fun validFilter(service: String, value: String) = (service == "movies" || service == "series") && value.length in 1..512 && value.none(Char::isISOControl)
    /** Remembered focus is "row␂item". A value saved by an earlier release is the bare item id. */
    fun focusValue(row: String?, item: String) = if (row.isNullOrBlank() || FOCUS_ROW in row) item else "$row$FOCUS_ROW$item"
    fun focusItem(value: String) = value.substringAfter(FOCUS_ROW)
    fun focusRow(value: String): String? = value.substringBefore(FOCUS_ROW, "").ifEmpty { null }
    fun titleKey(item: ContentItem): String? {
        val type = item.metadata["type"]?.takeIf { it == "movie" || it == "series" } ?: return null
        return if (item.source == "stremio" && imdb.matches(item.id)) "$type:${item.id}" else null
    }
    /** A saved show keeps just enough to draw its card: key, title, poster and year. */
    fun saved(item: ContentItem) = listOf(titleKey(item).orEmpty(), item.title.take(200),
        item.imageUrl?.takeIf { it.startsWith("https://") && it.length <= 1024 }.orEmpty(), item.subtitle?.take(40).orEmpty())
        .joinToString(FIELD.toString()) { it.replace(FIELD, ' ') }
    fun savedKey(value: String) = value.substringBefore(FIELD)
    fun savedItem(value: String): ContentItem? {
        return titleItem(value)?.takeIf { it.metadata["type"] == "series" }
    }
    private fun titleItem(value: String): ContentItem? {
        val parts = value.split(FIELD)
        if (parts.size != 4 || !titleKeyPattern.matches(parts[0])) return null
        val type = parts[0].substringBefore(':')
        val id = parts[0].substringAfter(':')
        return ContentItem(id, "stremio", parts[1], subtitle = parts[3].ifBlank { null }, imageUrl = parts[2].ifBlank { null },
            playbackUri = "stremio:///detail/$type/$id", metadata = mapOf("type" to type, "package" to "com.stremio.one", "saved" to "true"))
    }
    fun toggleStarred(state: ContentPreferenceState, item: ContentItem): ContentPreferenceState {
        val key = titleKey(item) ?: return state
        if (state.starredTitles.none { savedKey(it) == key } && state.starredTitles.size >= MAX_SAVED) return state
        val removing = state.starredTitles.any { savedKey(it) == key }
        val next = state.copy(starredTitles = if (removing)
            state.starredTitles.filterNot { savedKey(it) == key }
        else listOf(saved(item)) + state.starredTitles,
            starredMetadata = if (removing) state.starredMetadata - key else state.starredMetadata)
        return if (removing) next else enrichStarredMetadata(next, item)
    }
    fun starredFeed(state: ContentPreferenceState): FeedResult {
        val titles = sortedStarred(state)
        val rows = listOf("movie" to "Movies", "series" to "TV shows").mapNotNull { (type, label) ->
            ContentRow("starred-$type", label, titles.filter { it.metadata["type"] == type }).takeIf { it.items.isNotEmpty() }
        }
        return FeedResult(rows, if (rows.isEmpty()) com.majendra.tvlauncher.data.FeedStatus.NO_CONTENT else com.majendra.tvlauncher.data.FeedStatus.FRESH,
            if (rows.isEmpty() && state.starredTitles.isNotEmpty()) "No starred titles match this genre. Open Starred preferences to change it."
            else if (rows.isEmpty()) "Hold OK on a movie or show and choose Star to save it here." else "")
    }
    private fun validTitleKey(key: String) = titleKeyPattern.matches(key)
    fun toggleNotInterested(state: ContentPreferenceState, item: ContentItem): ContentPreferenceState {
        val key = titleKey(item) ?: return state
        return state.copy(notInterested = state.notInterested.toMutableSet().apply {
            if (!remove(key) && size < MAX_HIDDEN) add(key)
        })
    }
    fun restoreNotInterested(state: ContentPreferenceState, type: String?): ContentPreferenceState =
        state.copy(notInterested = state.notInterested.filterNot { type == null || it.startsWith("$type:") }.toSet())
    fun filterNotInterested(service: String, result: FeedResult, state: ContentPreferenceState): FeedResult {
        if (service == "starred") return result // Explicit stars remain available and independently reversible.
        return result.copy(rows = result.rows.map { row -> row.copy(items = row.items.filterNot { titleKey(it) in state.notInterested }) })
    }
    fun enrichStarredMetadata(state: ContentPreferenceState, item: ContentItem): ContentPreferenceState {
        val key = titleKey(item) ?: return state
        if (state.starredTitles.none { savedKey(it) == key }) return state
        val old = state.starredMetadata[key] ?: StarredMetadata()
        val rating = item.metadata["imdbRating"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 }
        val genres = item.metadata["genres"].orEmpty().split('|').map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 80 && it.none { c -> c.isISOControl() } }.distinct().take(20)
        val metadata = StarredMetadata(rating ?: old.rating, genres.ifEmpty { old.genres })
        if (metadata == StarredMetadata()) return state
        return state.copy(starredMetadata = state.starredMetadata + (key to metadata))
    }
    fun knownStarredGenres(state: ContentPreferenceState): List<String> = state.starredTitles
        .flatMap { state.starredMetadata[savedKey(it)]?.genres.orEmpty() }.distinct().sortedBy { it.lowercase() }
    private fun sortedStarred(state: ContentPreferenceState): List<ContentItem> {
        val titles = state.starredTitles.mapNotNull(::titleItem).distinctBy { titleKey(it) }.map { item ->
            val meta = state.starredMetadata[titleKey(item)]
            item.copy(metadata = item.metadata + buildMap {
                meta?.rating?.let { put("imdbRating", it.toString()) }
                if (!meta?.genres.isNullOrEmpty()) put("genres", meta!!.genres.joinToString("|"))
            })
        }.filter { state.starredGenre == null || state.starredGenre in state.starredMetadata[titleKey(it)]?.genres.orEmpty() }
        return when (state.starredSort) {
            "rating" -> titles.sortedWith(compareByDescending<ContentItem> { state.starredMetadata[titleKey(it)]?.rating ?: -1.0 })
            "genre" -> titles.sortedWith(compareBy<ContentItem> { state.starredMetadata[titleKey(it)]?.genres.isNullOrEmpty() }
                .thenBy { state.starredMetadata[titleKey(it)]?.genres?.minByOrNull { genre -> genre.lowercase() }?.lowercase().orEmpty() })
            else -> titles
        }
    }
    fun rowKey(service: String, row: String) = "$service\u0000$row"
    /** Personal rows have already been filtered; move the visible Tonight row without recreating it. */
    fun orderPersonalRows(service: String, result: FeedResult, includePersonalRows: Boolean): FeedResult {
        if (!includePersonalRows || service !in setOf("movies", "series")) return result
        val tonight = result.rows.firstOrNull { it.id == "watch-tonight" && it.items.isNotEmpty() } ?: return result
        val keys = tonight.items.mapNotNull(::titleKey).toSet()
        val rest = result.rows.filterNot { it === tonight }.map { row ->
            // Personal membership is retained independently; eligibility prevents overlap with Tonight.
            if (row.id in setOf("movies-continue-watching", "watching-shows")) row
            else row.copy(items = row.items.filterNot { titleKey(it) in keys })
        }
        return result.copy(rows = listOf(tonight) + rest)
    }
    /** Badge state comes only from the local encrypted selection, never from a source feed. */
    fun decorateStars(service: String, result: FeedResult, state: ContentPreferenceState): FeedResult {
        val keys = if (service in setOf("movies", "series", "starred", "stremio"))
            state.starredTitles.map(::savedKey).toSet() else emptySet()
        return result.copy(rows = result.rows.map { row -> row.copy(items = row.items.map { item ->
            val metadata = item.metadata - "isStarred"
            val next = if (titleKey(item) in keys) metadata + ("isStarred" to "true") else metadata
            if (next == item.metadata) item else item.copy(metadata = next)
        }) })
    }
    fun channelKey(item: ContentItem): String? {
        if (item.source !in channelServices || !item.metadata["inputId"].isNullOrBlank()) return null
        val channel = item.metadata["channelId"]?.takeIf { it.isNotBlank() }
            ?: item.metadata["channelName"]?.takeIf { it.isNotBlank() } ?: return null
        return rowKey(item.source, channel.take(2048))
    }
    fun apply(service: String, result: FeedResult, state: ContentPreferenceState, includePersonalRows: Boolean = true): FeedResult {
        if (service == "starred") {
            val rows = result.rows.filter { rowKey(service, it.id) !in state.hiddenRows }
            return if (rows.isEmpty() && result.rows.any { it.items.isNotEmpty() })
                result.copy(rows = rows, message = "Your Starred rows are hidden. Open Settings → Content rows & favourites.")
            else result.copy(rows = rows)
        }
        // Prefer Now over Next for each favourite, even when a source emits rows in a different order.
        val favorites = result.rows.asSequence().flatMap { it.items.asSequence() }
            .filter { it.source == service && channelKey(it) in state.favorites }
            .sortedWith(compareByDescending<ContentItem> { it.isLive || it.metadata["onAir"] == "true" }
                .thenBy { it.startTime ?: Long.MAX_VALUE })
            .distinctBy { channelKey(it) }.toList()
        val rows = result.rows.filter { rowKey(service, it.id) !in state.hiddenRows &&
            (includePersonalRows || it.id !in setOf("watching-shows", "movies-continue-watching", "saved-shows")) }
            .map { row -> row.copy(items = row.items.distinctBy { item -> item.id }.filter { item ->
                val resumeRow = service == "movies" && row.id == "movies-continue-watching"
                val unfinished = resumeRow && item.source == "stremio" &&
                    item.metadata["type"] == "movie" && item.metadata["accountAuthorised"] == "true" && item.metadata["stremioResume"] == "true"
                titleKey(item) !in state.notInterested && (unfinished || titleKey(item)?.let { key -> key in state.hiddenTitles } != true)
            }) }
        val saved = if (service == "series" && includePersonalRows) state.savedShows.mapNotNull(::savedItem)
            .filter { titleKey(it) !in state.hiddenTitles && titleKey(it) !in state.notInterested } else emptyList()
        val first = listOfNotNull(
            ContentRow("saved-shows", "My shows", saved).takeIf { saved.isNotEmpty() && rowKey(service, it.id) !in state.hiddenRows },
            ContentRow("favourites", "Favourite channels", favorites).takeIf { favorites.isNotEmpty() && rowKey(service, it.id) !in state.hiddenRows })
        return result.copy(rows = first + rows)
    }
    fun encode(state: ContentPreferenceState): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            // A saved filter is the only version 5 field: until one exists the file stays readable by earlier releases.
            out.writeInt(if (state.filters.isEmpty()) 4 else 5)
            listOf(state.favorites, state.hiddenRows).forEach { values ->
                out.writeInt(values.size); values.sorted().forEach { out.writeUTF(it) }
            }
            out.writeInt(state.focus.size)
            state.focus.toSortedMap().forEach { (service, id) -> out.writeUTF(service); out.writeUTF(id) }
            out.writeInt(state.hiddenTitles.size); state.hiddenTitles.sorted().forEach { out.writeUTF(it) }
            out.writeInt(state.savedShows.size); state.savedShows.forEach { out.writeUTF(it) } // Saved order is kept.
            out.writeInt(state.starredTitles.size); state.starredTitles.forEach { out.writeUTF(it) }
            out.writeInt(state.notInterested.size); state.notInterested.sorted().forEach { out.writeUTF(it) }
            out.writeUTF(state.starredSort); out.writeUTF(state.starredGenre.orEmpty())
            out.writeInt(state.starredMetadata.size)
            state.starredMetadata.toSortedMap().forEach { (key, metadata) ->
                out.writeUTF(key); out.writeDouble(metadata.rating ?: -1.0)
                out.writeInt(metadata.genres.size); metadata.genres.forEach(out::writeUTF)
            }
            if (state.filters.isNotEmpty()) {
                out.writeInt(state.filters.size)
                state.filters.toSortedMap().forEach { (service, filter) -> out.writeUTF(service); out.writeUTF(filter) }
            }
        }
    }.toByteArray()
    fun decode(bytes: ByteArray): ContentPreferenceState {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(bytes.size <= 4_194_304)
        require(version in 1..5)
        fun readList(limit: Int): List<String> {
            val size = input.readInt(); require(size in 0..limit)
            return List(size) { input.readUTF().also { require(it.length <= 4096) } }
        }
        fun readSet(limit: Int = 256) = readList(limit).toSet()
        val favorites = readSet(); val hidden = readSet()
        val size = input.readInt(); require(size in 0..services.size)
        val focus = buildMap { repeat(size) {
            val service = input.readUTF(); val id = input.readUTF()
            require(valid(service, id)); put(service, id)
        } }
        val hiddenTitles = if (version >= 2) readSet(MAX_HIDDEN) else emptySet()
        val saved = if (version >= 2) readList(MAX_SAVED) else emptyList()
        val starred = if (version >= 3) readList(MAX_SAVED) else emptyList()
        val notInterested = if (version >= 4) readSet(MAX_HIDDEN).also { require(it.all(::validTitleKey)) } else emptySet()
        val sort = if (version >= 4) input.readUTF().also { require(it in setOf("recent", "rating", "genre")) } else "recent"
        val genre = if (version >= 4) input.readUTF().also { require(it.length <= 80 && it.none(Char::isISOControl)) }.ifBlank { null } else null
        val metadata = if (version >= 4) {
            val count = input.readInt(); require(count in 0..MAX_SAVED)
            buildMap { repeat(count) {
                val key = input.readUTF(); require(validTitleKey(key) && starred.any { savedKey(it) == key })
                val rating = input.readDouble(); require(rating == -1.0 || rating.isFinite() && rating in 0.0..10.0)
                val genres = readList(20).also { require(it.all { g -> g.isNotBlank() && g.length <= 80 && g.none(Char::isISOControl) }) }
                put(key, StarredMetadata(rating.takeUnless { it == -1.0 }, genres))
            } }
        } else emptyMap()
        val filters = if (version >= 5) {
            val count = input.readInt(); require(count in 1..2)
            buildMap { repeat(count) { val service = input.readUTF(); val filter = input.readUTF(); require(validFilter(service, filter)); put(service, filter) } }
        } else emptyMap()
        require(input.available() == 0)
        return ContentPreferenceState(favorites, hidden, focus, hiddenTitles, saved, starred, notInterested, sort, genre, metadata, filters)
    }
}
