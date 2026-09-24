package com.example.ehviewer_scaffold.rust

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when a native image fetch fails. */
class RustImageFetchException(message: String) : RuntimeException(message)

object EhRustBridge {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * How many Rust calls may be in flight at once.
     *
     * Every bridge call ends in `RUNTIME.block_on(...)` on the Rust side, which
     * occupies **the calling Kotlin thread** for the entire network round-trip.
     * Because the image pipeline allows far more concurrent requests than the
     * thread pool has threads, an unthrottled burst would occupy every thread in
     * `Dispatchers.IO` and starve unrelated IO work (bitmap decode scheduling,
     * DataStore, file reads).
     *
     * 24 keeps a healthy working set in flight — enough to saturate a phone
     * connection — while leaving the rest of the pool free. It is deliberately
     * below `Dispatchers.IO`'s default parallelism (64) so the pool can never be
     * fully consumed by blocked-on-JNI threads.
     */
    private const val JNI_MAX_INFLIGHT = 24

    private val jniGate = Semaphore(JNI_MAX_INFLIGHT)

    /**
     * Runs [block] on [Dispatchers.IO] while holding a JNI slot.
     *
     * Use this instead of a bare `withContext(Dispatchers.IO) { ... }` for any
     * call that crosses into Rust — see [JNI_MAX_INFLIGHT].
     */
    internal suspend fun <T> withJniSlot(block: () -> T): T =
        jniGate.withPermit { withContext(Dispatchers.IO) { block() } }

    // ─── Native JNI Declarations ──────────────────────────────────────────

    @JvmStatic
    external fun healthCheck(name: String): String

    @JvmStatic
    external fun initBackend(cacheDir: String, enableEhHost: Boolean): Boolean

    @JvmStatic
    external fun syncCookies(cookieStr: String): Boolean

    @JvmStatic
    external fun setUserAgent(ua: String): Boolean

    @JvmStatic
    external fun setSiteUrl(url: String): Boolean

    @JvmStatic
    external fun fetchFrontPage(query: String?, optionsJson: String?): String

    /**
     * [forceRefresh] bypasses the listing's 300-second disk cache. Set it for an
     * explicit pull-to-refresh: without it the refresh is served from cache in a
     * few milliseconds, so the list never changes and the spinner barely appears.
     */
    @JvmStatic
    external fun fetchGalleryList(page: Int, pageUrl: String?, query: String?, optionsJson: String?, forceRefresh: Boolean): String

    @JvmStatic
    external fun fetchCustomList(path: String, page: Int, pageUrl: String?, query: String?, optionsJson: String?, forceRefresh: Boolean): String

    @JvmStatic
    external fun fetchGalleryDetail(id: String): String

    @JvmStatic
    external fun fetchGalleryPage(id: String, token: String, page: Int): String

    @JvmStatic
    external fun resolveImageUrl(viewerUrl: String): String

    /**
     * Unified reader image pipeline (resolve + nl-retry + LRU Disk cache).
     * Returns "file://<absolute_path>" for Coil to decode locally.
     * Returns empty string on unrecoverable error.
     * Called from [RustImageFetcher] on Dispatchers.IO.
     */
    @JvmStatic
    external fun fetchAndCacheImage(viewerUrl: String): String

    /**
     * Retrieves real-time download progress for a reader viewer page (0.0f .. 1.0f)
     */
    @JvmStatic
    external fun getImageProgress(viewerUrl: String): Float

    @JvmStatic
    external fun startDownload(gid: String, token: String, title: String, imageUrlsJson: String, totalPages: Int): Boolean

    @JvmStatic
    external fun pauseDownload(gid: String): Boolean

    /**
     * Restart a stopped or failed download from its stored page list.
     *
     * Distinct from [pauseDownload]: the download manager's play button used to
     * be wired to pause, so a stopped task could never be restarted.
     */
    @JvmStatic
    external fun resumeDownload(gid: String): Boolean

    /**
     * Absolute paths of a gallery's downloaded pages (page-ordered) as a JSON
     * array. Backs the offline reader.
     */
    @JvmStatic
    external fun getDownloadedPages(gid: String): String

    @JvmStatic
    external fun deleteDownload(gid: String): Boolean

    @JvmStatic
    external fun getDownloadTasks(): String

    @JvmStatic
    external fun setDownloadConcurrency(n: Int): Boolean

    @JvmStatic
    external fun setDownloadDir(path: String): Boolean

    @JvmStatic
    external fun getDownloadDir(): String

    @JvmStatic
    external fun fetchTorrents(gid: String, token: String): String

    /**
     * Fetch one `.torrent` file from the tracker and save it under the app's
     * downloads directory (`<downloads>/torrents/<name>.torrent`).
     *
     * Returns the saved absolute path as a JSON string, or `{"error": "..."}`.
     */
    @JvmStatic
    external fun downloadTorrent(name: String, hash: String, token: String): String

    @JvmStatic
    external fun addFavorite(gid: String, token: String, favcat: String, favnote: String): String

    @JvmStatic
    external fun postComment(gid: String, token: String, content: String): String

    @JvmStatic
    external fun voteGallery(gid: String, token: String, rating: Int): String

    @JvmStatic
    external fun voteComment(url: String): String

    @JvmStatic
    external fun fetchMoreComments(gid: String, token: String, page: Int): String

    @JvmStatic
    external fun translateTagSync(namespace: String, tag: String): String

    /**
     * Batch variant of [translateTagSync]: a JSON array of `[["ns","tag"], ...]`
     * in, a JSON object of `{"ns:tag": "translation"}` out.
     *
     * One JNI round-trip and one TAG_DB lock acquisition for the whole batch,
     * instead of one of each per tag.
     */
    @JvmStatic
    external fun translateTagsJson(pairsJson: String): String

    @JvmStatic
    external fun searchTagByChinese(keyword: String): String

    /**
     * E-Hentai's own completion endpoint (`api.php` -> `tagsuggest`).
     *
     * This is a network call: the caller is expected to debounce it and to
     * merge its result with the local [searchTags] suggestions, never to put
     * it on the keystroke path directly.
     */
    @JvmStatic
    external fun suggestTagsOnline(keyword: String): String

    @JvmStatic
    external fun fetchEhWebConfig(): String

    @JvmStatic
    external fun downloadTagDb(path: String): Boolean

    @JvmStatic
    external fun loadTagDb(path: String): Boolean

    /**
     * Replace the blocked-tag list held by Rust.
     *
     * The authoritative copy is in SharedPreferences; Rust keeps an in-memory
     * mirror so listing filtering needs no JNI round-trip per item, and so the
     * Kotlin search path can append the tags as `-ns:tag` exclusions.
     *
     * `tagsJson` is a JSON array of strings, each either `namespace:tag` or a
     * bare tag typed by hand.
     */
    @JvmStatic
    external fun setBlockedTags(tagsJson: String): Boolean

    /**
     * Given a JSON array of gids, return the JSON array of those that are *not*
     * blocked. Used to prune an already-loaded list when the block list changes.
     */
    @JvmStatic
    external fun filterBlockedGalleryIds(gidsJson: String): String

    /**
     * Delete disk-cache files older than [olderThanDays] days and flush L1 memory cache.
     * Returns bytes freed (≥0) or -1 on error.
     */
    @JvmStatic
    external fun clearExpiredCache(olderThanDays: Int): Long

    /**
     * Add a tag to the account's My Tags watched list (subscribes it so matching
     * galleries appear on the /watched page). Requires an active login session.
     * Returns the server response message or empty string on error.
     */
    @JvmStatic
    external fun addWatchedTag(tag: String): String

    /**
     * Remove a tag from the account's My Tags watched list.
     * Returns the server response message or empty string on error.
     */
    @JvmStatic
    external fun removeWatchedTag(tag: String): String

    // ─── Safe Deserialization Helper ──────────────────────────────────────

    inline fun <reified T> safeDecode(raw: String, fallback: T): T {
        return runCatching {
            if (raw.startsWith("{\"error\"") && !raw.contains("\"items\"") && !raw.contains("\"id\"")) {
                android.util.Log.e("EhRustBridge", "safeDecode error payload: $raw")
                fallback
            } else {
                json.decodeFromString<T>(raw)
            }
        }.getOrElse {
            android.util.Log.e("EhRustBridge", "safeDecode failed to parse: $raw", it)
            fallback
        }
    }

    // ─── Coroutine-friendly Suspend Wrappers ──────────────────────────────

    suspend fun getFrontPage(query: String? = null, options: SearchOptions? = null): List<GalleryItem> = withJniSlot {
        val optJson = options?.let { json.encodeToString(SearchOptions.serializer(), it) }
        val raw = fetchFrontPage(query, optJson)
        safeDecode(raw, emptyList())
    }

    suspend fun getGalleryList(
        page: Int,
        pageUrl: String? = null,
        query: String? = null,
        options: SearchOptions? = null,
        forceRefresh: Boolean = false
    ): GalleryPage = withJniSlot {
        val optJson = options?.let { json.encodeToString(SearchOptions.serializer(), it) }
        val raw = fetchGalleryList(page, pageUrl, query, optJson, forceRefresh)
        safeDecode(raw, GalleryPage())
    }

    suspend fun getCustomList(
        path: String,
        page: Int,
        pageUrl: String? = null,
        query: String? = null,
        options: SearchOptions? = null,
        forceRefresh: Boolean = false
    ): GalleryPage = withJniSlot {
        val optJson = options?.let { json.encodeToString(SearchOptions.serializer(), it) }
        val raw = fetchCustomList(path, page, pageUrl, query, optJson, forceRefresh)
        safeDecode(raw, GalleryPage())
    }

    suspend fun getGalleryDetail(id: String): GalleryDetail = withJniSlot {
        val raw = fetchGalleryDetail(id)
        safeDecode(raw, GalleryDetail())
    }

    suspend fun getGalleryPage(id: String, token: String, page: Int): GalleryDetail = withJniSlot {
        val raw = fetchGalleryPage(id, token, page)
        safeDecode(raw, GalleryDetail())
    }

    suspend fun getResolvedImageUrl(viewerUrl: String): String = withJniSlot {
        resolveImageUrl(viewerUrl)
    }

    suspend fun getDownloads(): List<DownloadTask> = withJniSlot {
        val raw = getDownloadTasks()
        safeDecode(raw, emptyList())
    }

    /** Resume a stopped/failed download. Returns false when it cannot start. */
    suspend fun resumeDownloadTask(gid: String): Boolean = withJniSlot {
        resumeDownload(gid)
    }

    /** Absolute page paths of a finished download, in page order. */
    suspend fun getDownloadedPagePaths(gid: String): List<String> = withJniSlot {
        safeDecode(getDownloadedPages(gid), emptyList())
    }

    /**
     * Prune blocked galleries out of a list that is already on screen.
     *
     * Returns `gids` unchanged when nothing is blocked or Rust is unavailable, so
     * a caller can never accidentally empty its list because of a failed call.
     */
    suspend fun keepUnblockedGalleryIds(gids: List<String>): List<String> {
        if (gids.isEmpty()) return gids
        return withJniSlot {
            safeDecode(filterBlockedGalleryIds(json.encodeToString(gids)), gids)
        }
    }

    suspend fun triggerDownload(gid: String, token: String, title: String, imageUrls: List<String>, totalPages: Int): Boolean = withJniSlot {
        val urlsJson = json.encodeToString(imageUrls)
        startDownload(gid, token, title, urlsJson, totalPages)
    }

    suspend fun getTorrents(gid: String, token: String): List<TorrentItem> = withJniSlot {
        val raw = fetchTorrents(gid, token)
        safeDecode(raw, emptyList())
    }

    /**
     * Download one torrent file. Returns the saved path, or `""` on failure
     * (the native side reports the reason in its log).
     */
    suspend fun fetchTorrentFile(name: String, hash: String, token: String): String = withJniSlot {
        safeDecode(downloadTorrent(name, hash, token), "")
    }

    suspend fun submitFavorite(gid: String, token: String, favcat: String, favnote: String): String = withJniSlot {
        addFavorite(gid, token, favcat, favnote)
    }

    suspend fun sendComment(gid: String, token: String, content: String): String = withJniSlot {
        postComment(gid, token, content)
    }

    suspend fun rate(gid: String, token: String, rating: Int): String = withJniSlot {
        voteGallery(gid, token, rating)
    }

    suspend fun voteOnComment(url: String): String = withJniSlot {
        voteComment(url)
    }

    suspend fun getMoreComments(gid: String, token: String, page: Int): List<GalleryComment> = withJniSlot {
        val raw = fetchMoreComments(gid, token, page)
        safeDecode(raw, emptyList())
    }

    /**
     * Translate a single tag.
     *
     * ⚠️ Blocking: this crosses JNI into Rust and takes a read lock on the
     * shared TAG_DB, so it must never be called from the main thread. It is
     * `suspend` and dispatches itself to [Dispatchers.IO] precisely so callers
     * cannot accidentally freeze the UI — previously it was a plain function
     * that ran inline, which made any call from a recomposition path a jank bomb.
     *
     * Prefer [translateTags] when translating more than one tag.
     */
    suspend fun translateTag(namespace: String, tag: String): String = withJniSlot {
        translateTagSync(namespace, tag)
    }

    /**
     * Translate every `(namespace, tag)` pair in **one** JNI call.
     *
     * Galleries routinely carry 60–120 tags. The previous implementation only
     * collapsed the thread switches, not the JNI hops: it still made one native
     * call *and* one TAG_DB lock acquisition per tag, so opening a detail page
     * paid 60–120 boundary crossings. This sends the whole batch at once.
     *
     * Keys are the raw `"namespace:tag"` strings, matching what the detail
     * screen uses to index its translation map.
     */
    suspend fun translateTags(tags: List<Pair<String, String>>): Map<String, String> {
        if (tags.isEmpty()) return emptyMap()
        return withJniSlot {
            // Pair is not @Serializable, so send the pairs as a plain array of
            // two-element arrays — the Rust side deserialises Vec<(String,String)>.
            val payload = json.encodeToString(tags.map { listOf(it.first, it.second) })
            safeDecode(translateTagsJson(payload), emptyMap())
        }
    }

    suspend fun searchTags(keyword: String): List<TagSuggestion> = withJniSlot {
        val raw = searchTagByChinese(keyword)
        safeDecode(raw, emptyList())
    }

    /**
     * Server-side English completion. Returns an empty list when offline; the
     * caller treats that as "nothing to merge" rather than an error.
     */
    suspend fun getOnlineTagSuggestions(keyword: String): List<TagSuggestion> = withJniSlot {
        val raw = suggestTagsOnline(keyword)
        safeDecode(raw, emptyList())
    }

    suspend fun getEhWebConfig(): EhWebConfig = withJniSlot {
        val raw = fetchEhWebConfig()
        safeDecode(raw, EhWebConfig())
    }

    // ─── Async image fetch (Rust-driven, cancellable) ─────────────────────
    //
    // Everything above blocks its calling thread inside Rust's `block_on`, which
    // is why `withJniSlot` has to exist at all. The reader image path is the one
    // that is high-frequency, naturally cancellable, and returns a small value,
    // so it is the one path that runs fully async: Rust owns the task and calls
    // back when it finishes, and the coroutine below stays parked in the
    // meantime, consuming no JVM thread at all.

    /**
     * Coroutines parked on a Rust image task, keyed by task id.
     *
     * The `ConcurrentHashMap` is load-bearing, not incidental: the cancellation
     * handler and the Rust callback both try to claim the continuation with
     * `remove`, and `remove` is atomic, so exactly one of them wins. The loser
     * gets `null` and does nothing — which is what makes a late callback arriving
     * after cancellation harmless instead of a crash or a double resume.
     */
    private val pendingImageTasks = ConcurrentHashMap<Long, CancellableContinuation<String>>()
    private val imageTaskIds = AtomicLong(1L)

    @JvmStatic
    external fun nativeSubmitImageFetch(taskId: Long, viewerUrl: String)

    @JvmStatic
    external fun nativeCancelImageFetch(taskId: Long)

    /**
     * Resolve + download a reader page, returning its local file path.
     *
     * Holds **no thread** while waiting — the coroutine is parked and Rust drives
     * the request on its own runtime. Cancelling the calling coroutine (page
     * scrolled away, screen closed) aborts the native task and drops the socket.
     *
     * Returns a *path*, not the image bytes: the file is already on disk, and
     * copying a multi-megabyte array across JNI would defeat the disk cache and
     * undo the zero-copy work done on the Rust side.
     */
    suspend fun fetchImagePath(viewerUrl: String): String = suspendCancellableCoroutine { cont ->
        val taskId = imageTaskIds.getAndIncrement()

        // Register *before* submitting, so a callback that arrives immediately
        // still finds its continuation.
        pendingImageTasks[taskId] = cont

        cont.invokeOnCancellation {
            // Claim the entry first: if Rust already delivered the result, its
            // callback already removed it and this becomes a no-op.
            pendingImageTasks.remove(taskId)
            nativeCancelImageFetch(taskId)
        }

        nativeSubmitImageFetch(taskId, viewerUrl)
    }

    /** Invoked from a Rust worker thread. */
    @JvmStatic
    fun onNativeImageSuccess(taskId: Long, path: String) {
        val cont = pendingImageTasks.remove(taskId) ?: return
        if (cont.isActive) cont.resume(path)
    }

    /** Invoked from a Rust worker thread. */
    @JvmStatic
    fun onNativeImageError(taskId: Long, message: String) {
        val cont = pendingImageTasks.remove(taskId) ?: return
        if (cont.isActive) cont.resumeWithException(RustImageFetchException(message))
    }
}
