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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // JNI 调用在 IO 线程执行（Rust 内部 block_on Tokio runtime）
        val localFileUri = withContext(Dispatchers.IO) {
            EhRustBridge.fetchAndCacheImage(viewerUrl)
        }

        if (localFileUri.isEmpty()) {
            return null
        }

        // Strip "file://" scheme; okio needs the raw filesystem path
        val rawPath = localFileUri.removePrefix("file://")
        val path = rawPath.toPath()
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
