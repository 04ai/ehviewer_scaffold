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
 */
object GalleryDetailCache {
    private val cache = LruCache<String, GalleryDetail>(60)

    fun get(key: String): GalleryDetail? = synchronized(cache) { cache.get(key) }
    fun put(key: String, detail: GalleryDetail) = synchronized(cache) { cache.put(key, detail) }
    fun remove(key: String) = synchronized(cache) { cache.remove(key) }
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
