package com.example.ehviewer_scaffold.data

import android.util.LruCache
import androidx.compose.runtime.mutableStateListOf
import com.example.ehviewer_scaffold.rust.GalleryDetail
import com.example.ehviewer_scaffold.rust.GalleryItem
import com.example.ehviewer_scaffold.ui.screens.DrawerNavCategory

/**
 * In-memory LRU cache for parsed GalleryDetail objects.
 *
 * Ensures that returning from GalleryReaderScreen to GalleryDetailScreen
 * immediately renders the detail page from memory (0ms latency, zero network requests,
 * no loading spinner).
 *
 * Sized by *weight*, not by entry count: a 500-page gallery carries ~25x the
 * image URLs, thumbnails and comments of a 20-page one, so counting them equally
 * let a handful of large galleries evict the rest while still 'fitting' in the
 * limit. The budget is expressed in thumbnail-pages so the number stays readable.
 */
object GalleryDetailCache {
    /** Total weight allowed, in units of "pages worth of metadata". */
    private const val MAX_WEIGHT = 6_000

    private val cache = object : LruCache<String, GalleryDetail>(MAX_WEIGHT) {
        override fun sizeOf(key: String, value: GalleryDetail): Int =
            (value.totalPages.coerceAtLeast(1)
                + value.thumbnails.size
                + value.comments.size).coerceAtMost(MAX_WEIGHT)
    }

    fun get(key: String): GalleryDetail? = cache.get(key)
    fun put(key: String, detail: GalleryDetail) = cache.put(key, detail)
    fun remove(key: String) = cache.remove(key)
}

/**
 * Retained state holder for the HomeScreen gallery list.
 *
 * Prevents the HomeScreen from clearing and refetching all items over the network
 * when navigating to GalleryDetailScreen and popping back.
 */
object HomeStateHolder {
    val galleryItems = mutableStateListOf<GalleryItem>()
    var currentPage: Int = 0
    var currentQuery: String? = null
    var currentNextUrl: String? = null
    var selectedCategory: DrawerNavCategory = DrawerNavCategory.HOME
    var hasMore: Boolean = true
    var isInitialized: Boolean = false

    fun clear() {
        galleryItems.clear()
        currentPage = 0
        currentQuery = null
        currentNextUrl = null
        hasMore = true
        isInitialized = false
    }
}

/**
 * In-memory holder for the most recently clicked GalleryItem(s).
 * Enables instant skeleton rendering with title, cover, uploader, category
 * the microsecond GalleryDetailScreen is opened.
 */
object GalleryPreviewHolder {
    private val cache = LruCache<String, GalleryItem>(50)

    fun get(gid: String): GalleryItem? = cache.get(gid)
    fun put(item: GalleryItem) = cache.put(item.gid, item)
}

/**
 * In-memory LRU cache for resolved tag translations, keyed by `"$gid/$token"`.
 *
 * Every tag translation crosses the JNI boundary into Rust. A gallery can easily
 * carry 60-120 tags, so re-resolving them on every visit to the detail screen
 * meant a large batch of native calls on the critical path, stalling the first
 * frame after navigation. Caching the resolved map per gallery removes that cost
 * entirely on revisits (tag-search → detail → back → detail is the common loop).
 */
object TagTranslationCache {
    private val cache = LruCache<String, Map<String, String>>(60)

    fun get(key: String): Map<String, String>? = cache.get(key)
    fun put(key: String, translations: Map<String, String>) = cache.put(key, translations)
    fun remove(key: String) = cache.remove(key)
}
