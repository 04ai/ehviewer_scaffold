package com.example.ehviewer_scaffold.coil

import coil3.ImageLoader
import coil3.Uri as CoilUri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.example.ehviewer_scaffold.rust.EhRustBridge
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 自定义 Coil Fetcher：将 E-Hentai / ExHentai viewer URL 路由给 Rust 引擎统一处理。
 *
 * 数据流：
 *   viewer_url (e-hentai.org/…) → [JNI] → Rust fetch_and_cache_image
 *     → resolve_image_url (真实 CDN 地址)
 *     → fetch_with_nl_retry (含 509/403 配额失效重试)
 *     → Disk LRU Cache 落地 (md5 key，app cache 目录)
 *     → 返回 file:// 本地路径
 *   [Coil] → 从 file:// 本地路径解码图片，完全不访问外网
 */
class RustImageFetcher(
    private val viewerUrl: String,
    @Suppress("unused") private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // Fully async: this suspends without holding a JVM thread while Rust does
        // the resolve + download on its own runtime, and cancelling the calling
        // coroutine (page scrolled away, screen closed) aborts the native task.
        // Previously this blocked a Dispatchers.IO thread for the whole
        // round-trip, which capped reader concurrency at the thread-pool size.
        val rawPath = try {
            EhRustBridge.fetchImagePath(viewerUrl)
        } catch (_: Exception) {
            return null
        }
        if (rawPath.isEmpty()) {
            return null
        }

        // Rust hands back a "file://" URI; okio wants the raw filesystem path.
        val path = rawPath.removePrefix("file://").toPath()
        if (!FileSystem.SYSTEM.exists(path)) {
            return null
        }

        return SourceFetchResult(
            source = ImageSource(path, FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * Factory for coil3.Uri
     */
    class Factory : Fetcher.Factory<CoilUri> {
        override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val s = data.toString()
            val isViewerPage = s.contains("e-hentai.org/s/") || s.contains("exhentai.org/s/")
            return if (isViewerPage) RustImageFetcher(s, options) else null
        }
    }

    /**
     * Factory for android.net.Uri
     */
    class AndroidUriFactory : Fetcher.Factory<android.net.Uri> {
        override fun create(data: android.net.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val s = data.toString()
            val isViewerPage = s.contains("e-hentai.org/s/") || s.contains("exhentai.org/s/")
            return if (isViewerPage) RustImageFetcher(s, options) else null
        }
    }

    /**
     * Factory for raw String URLs
     */
    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val isViewerPage = (data.contains("e-hentai.org/s/") || data.contains("exhentai.org/s/"))
            return if (isViewerPage) RustImageFetcher(data, options) else null
        }
    }
}
