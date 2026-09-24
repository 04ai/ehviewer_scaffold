package com.example.ehviewer_scaffold.rust

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable, immutable snapshot of a gallery list entry.
 * The @Immutable contract lets Compose skip recomposing LazyColumn items
 * whenever unrelated parent state (search query, loading flag, etc.) changes.
 */
@Immutable
@Serializable
data class GalleryItem(
    val gid: String = "",
    val token: String = "",
    val title: String = "",
    @SerialName("thumb_url") val thumbUrl: String = "",
    val category: String = "Non-H",
    val uploader: String = "",
    @SerialName("post_date") val postDate: String = "",
    /**
     * Real cover dimensions parsed from the listing HTML (0 when unavailable).
     *
     * Waterfall cells derive their aspect ratio from these so a wide cover is
     * laid out at its native ratio instead of being centre-cropped into a
     * portrait box.
     */
    @SerialName("thumb_width") val thumbWidth: Int = 0,
    @SerialName("thumb_height") val thumbHeight: Int = 0
) {
    /**
     * Cover width / height, clamped to a sane range.
     *
     * Falls back to 0.72 (the classic EH portrait thumb ratio) when the server
     * did not report usable dimensions. The clamp keeps pathological or
     * mis-parsed values from producing absurdly tall or short cells.
     */
    val coverAspectRatio: Float
        get() {
            if (thumbWidth <= 0 || thumbHeight <= 0) return DEFAULT_COVER_ASPECT
            val ratio = thumbWidth.toFloat() / thumbHeight.toFloat()
            return ratio.coerceIn(MIN_COVER_ASPECT, MAX_COVER_ASPECT)
        }

    companion object {
        /** Classic EH thumbnail proportions (250x346), used when unknown. */
        const val DEFAULT_COVER_ASPECT = 0.72f
        private const val MIN_COVER_ASPECT = 0.4f
        private const val MAX_COVER_ASPECT = 3.0f
    }
}

@Immutable
@Serializable
data class TagGroup(
    @SerialName("group_name") val groupName: String = "",
    val tags: List<String> = emptyList()
)

@Immutable
@Serializable
data class GalleryThumbnail(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("offset_x") val offsetX: Int = 0,
    @SerialName("offset_y") val offsetY: Int = 0
)

@Immutable
@Serializable
data class GalleryComment(
    val author: String = "",
    val time: String = "",
    val content: String = "",
    val id: Long = 0L,
    val score: Int = 0,
    @SerialName("vote_url") val voteUrl: String = ""
)

@Serializable
data class GalleryDetail(
    val id: String = "",
    val title: String = "",
    @SerialName("title_jpn") val titleJpn: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val uploader: String = "",
    val rating: String = "0.0",
    val language: String = "",
    @SerialName("file_size") val fileSize: String = "",
    @SerialName("post_date") val postDate: String = "",
    @SerialName("favorites_count") val favoritesCount: String = "0",
    @SerialName("torrent_count") val torrentCount: String = "0",
    @SerialName("tag_groups") val tagGroups: List<TagGroup> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    val thumbnails: List<GalleryThumbnail> = emptyList(),
    val comments: List<GalleryComment> = emptyList(),
    @SerialName("is_favorited") val isFavorited: Boolean = false
)

@Serializable
data class GalleryPage(
    val items: List<GalleryItem> = emptyList(),
    @SerialName("next_url") val nextUrl: String? = null
)

@Serializable
data class SearchOptions(
    @SerialName("f_sname") val fSname: Boolean = true,
    @SerialName("f_stags") val fStags: Boolean = true,
    @SerialName("f_sdesc") val fSdesc: Boolean = false,
    @SerialName("f_cats") val fCats: Int? = null
)

@Serializable
data class DownloadTask(
    val gid: String = "",
    val token: String = "",
    val title: String = "",
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("downloaded_pages") val downloadedPages: Int = 0,
    val status: Int = 0, // 0 = stopped, 1 = downloading, 2 = completed, -1 = error
    @SerialName("error_msg") val errorMsg: String? = null
)

@Serializable
data class TorrentItem(
    val name: String = "",
    val posted: String = "",
    @SerialName("size_text") val sizeText: String = "",
    val seeds: String = "0",
    val peers: String = "0",
    val downloads: String = "0",
    val uploader: String = "",
    val hash: String = "",
    val token: String = ""
)

@Serializable
data class TagSuggestion(
    val raw: String = "",
    val translated: String = ""
)

@Serializable
data class EhWebConfig(
    @SerialName("load_hath") val loadHath: String = "",
    @SerialName("image_size") val imageSize: String = "",
    @SerialName("image_width") val imageWidth: String = "",
    @SerialName("image_height") val imageHeight: String = "",
    @SerialName("title_display") val titleDisplay: String = "",
    @SerialName("archiver_settings") val archiverSettings: String = "",
    @SerialName("display_mode") val displayMode: String = "",
    @SerialName("favorite_names") val favoriteNames: List<String> = emptyList(),
    @SerialName("raw_params") val rawParams: Map<String, String> = emptyMap()
)
