package com.example.ehviewer_scaffold.rust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object EhRustBridge {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

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

    @JvmStatic
    external fun fetchGalleryList(page: Int, pageUrl: String?, query: String?, optionsJson: String?): String

    @JvmStatic
    external fun fetchCustomList(path: String, page: Int, pageUrl: String?, query: String?, optionsJson: String?): String

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

    @JvmStatic
    external fun startDownload(gid: String, token: String, title: String, imageUrlsJson: String, totalPages: Int): Boolean

    @JvmStatic
    external fun pauseDownload(gid: String): Boolean

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

    @JvmStatic
    external fun searchTagByChinese(keyword: String): String

    @JvmStatic
    external fun fetchEhWebConfig(): String

    @JvmStatic
    external fun downloadTagDb(path: String): Boolean

    @JvmStatic
    external fun loadTagDb(path: String): Boolean

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

    suspend fun getFrontPage(query: String? = null, options: SearchOptions? = null): List<GalleryItem> = withContext(Dispatchers.IO) {
        val optJson = options?.let { json.encodeToString(SearchOptions.serializer(), it) }
        val raw = fetchFrontPage(query, optJson)
        safeDecode(raw, emptyList())
    }

    suspend fun getGalleryList(
        page: Int,
        pageUrl: String? = null,
        query: String? = null,
        options: SearchOptions? = null
    ): GalleryPage = withContext(Dispatchers.IO) {
        val optJson = options?.let { json.encodeToString(SearchOptions.serializer(), it) }
        val raw = fetchGalleryList(page, pageUrl, query, optJson)
        safeDecode(raw, GalleryPage())
    }

    suspend fun getCustomList(
        path: String,
        page: Int,
        pageUrl: String? = null,
        query: String? = null,
        options: SearchOptions? = null
    ): GalleryPage = withContext(Dispatchers.IO) {
        val optJson = options?.let { json.encodeToString(SearchOptions.serializer(), it) }
        val raw = fetchCustomList(path, page, pageUrl, query, optJson)
        safeDecode(raw, GalleryPage())
    }

    suspend fun getGalleryDetail(id: String): GalleryDetail = withContext(Dispatchers.IO) {
        val raw = fetchGalleryDetail(id)
        safeDecode(raw, GalleryDetail())
    }

    suspend fun getGalleryPage(id: String, token: String, page: Int): GalleryDetail = withContext(Dispatchers.IO) {
        val raw = fetchGalleryPage(id, token, page)
        safeDecode(raw, GalleryDetail())
    }

    suspend fun getResolvedImageUrl(viewerUrl: String): String = withContext(Dispatchers.IO) {
        resolveImageUrl(viewerUrl)
    }

    suspend fun getDownloads(): List<DownloadTask> = withContext(Dispatchers.IO) {
        val raw = getDownloadTasks()
        safeDecode(raw, emptyList())
    }

    suspend fun triggerDownload(gid: String, token: String, title: String, imageUrls: List<String>, totalPages: Int): Boolean = withContext(Dispatchers.IO) {
        val urlsJson = json.encodeToString(imageUrls)
        startDownload(gid, token, title, urlsJson, totalPages)
    }

    suspend fun getTorrents(gid: String, token: String): List<TorrentItem> = withContext(Dispatchers.IO) {
        val raw = fetchTorrents(gid, token)
        safeDecode(raw, emptyList())
    }

    suspend fun submitFavorite(gid: String, token: String, favcat: String, favnote: String): String = withContext(Dispatchers.IO) {
        addFavorite(gid, token, favcat, favnote)
    }

    suspend fun sendComment(gid: String, token: String, content: String): String = withContext(Dispatchers.IO) {
        postComment(gid, token, content)
    }

    suspend fun rate(gid: String, token: String, rating: Int): String = withContext(Dispatchers.IO) {
        voteGallery(gid, token, rating)
    }

    suspend fun voteOnComment(url: String): String = withContext(Dispatchers.IO) {
        voteComment(url)
    }

    suspend fun getMoreComments(gid: String, token: String, page: Int): List<GalleryComment> = withContext(Dispatchers.IO) {
        val raw = fetchMoreComments(gid, token, page)
        safeDecode(raw, emptyList())
    }

    fun translateTag(namespace: String, tag: String): String {
        return translateTagSync(namespace, tag)
    }

    suspend fun searchTags(keyword: String): List<TagSuggestion> = withContext(Dispatchers.IO) {
        val raw = searchTagByChinese(keyword)
        safeDecode(raw, emptyList())
    }

    suspend fun getEhWebConfig(): EhWebConfig = withContext(Dispatchers.IO) {
        val raw = fetchEhWebConfig()
        safeDecode(raw, EhWebConfig())
    }
}
