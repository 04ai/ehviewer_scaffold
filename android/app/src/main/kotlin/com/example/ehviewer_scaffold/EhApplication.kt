package com.example.ehviewer_scaffold

import android.app.Application
import android.util.Log
import android.webkit.CookieManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.example.ehviewer_scaffold.coil.RustImageFetcher
import com.example.ehviewer_scaffold.rust.EhRustBridge
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toPath
import java.io.File

class EhApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        private const val TAG = "EhApplication"
        lateinit var instance: EhApplication
            private set

        /**
         * Volatile cookie string shared between the Application and the Coil OkHttp interceptor.
         * Written on the main thread after WebView login; read on network threads.
         */
        @Volatile
        var sharedCookieString: String = ""

        /**
         * Volatile UA string, set once after WebView default UA is resolved.
         */
        @Volatile
        var sharedUserAgent: String = ""
    }

    /**
     * OkHttp interceptor that injects the current E-Hentai session cookies
     * and user-agent into every Coil image request (covers + thumbnails).
     */
    private inner class EhCookieInterceptor : Interceptor {
        private var lastCookieCheckTime = 0L
        private var cachedCookieResult = ""

        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val host = originalRequest.url.host

            // Only inject cookies for EH/ExHentai domains (including CDN ehgt.org).
            val isEhHost = host.contains("e-hentai.org")
                    || host.contains("exhentai.org")
                    || host.contains("ehgt.org")
                    || host.contains("hath.network")

            if (!isEhHost) {
                return chain.proceed(originalRequest)
            }

            // Read cookies with 5s memory cache to avoid blocking OkHttp threads with
            // frequent synchronous Binder IPC calls to Android CookieManager.
            val cookieStr = getOrRefreshCookies()

            val requestBuilder = originalRequest.newBuilder()
            if (cookieStr.isNotEmpty()) {
                requestBuilder.header("Cookie", cookieStr)
            }
            val ua = sharedUserAgent
            if (ua.isNotEmpty()) {
                requestBuilder.header("User-Agent", ua)
            }
            // Essential: e-hentai CDN (ehgt.org) blocks image hotlinking with HTTP 403 without Referer
            requestBuilder.header("Referer", "https://e-hentai.org/")
            requestBuilder.header("Origin", "https://e-hentai.org")

            return chain.proceed(requestBuilder.build())
        }

        private fun getOrRefreshCookies(): String {
            if (sharedCookieString.isNotEmpty()) return sharedCookieString
            val now = System.currentTimeMillis()
            if (now - lastCookieCheckTime < 5000L && cachedCookieResult.isNotEmpty()) {
                return cachedCookieResult
            }
            lastCookieCheckTime = now
            val built = buildCookieStringFromManager()
            if (built.isNotEmpty()) {
                sharedCookieString = built
                cachedCookieResult = built
            }
            return built
        }

        private fun buildCookieStringFromManager(): String {
            return try {
                val cm = CookieManager.getInstance()
                val domains = listOf(
                    "https://e-hentai.org",
                    "https://exhentai.org",
                    "https://forums.e-hentai.org"
                )
                domains.mapNotNull { cm.getCookie(it)?.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("; ")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cookies from CookieManager: ${e.message}")
                ""
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            System.loadLibrary("ehviewer_rust_backend")
            Log.i(TAG, "Native library 'ehviewer_rust_backend' loaded successfully.")

            val cachePath = cacheDir.absolutePath
            val ok = EhRustBridge.initBackend(cachePath, false)
            Log.i(TAG, "Rust backend init result: $ok (cacheDir=$cachePath)")

            val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(this)
            sharedUserAgent = defaultUa
            EhRustBridge.setUserAgent(defaultUa)
            Log.i(TAG, "Synchronized system WebView User-Agent to Rust client: $defaultUa")

            val tagDb = File(filesDir, "tag_db.json")
            if (tagDb.exists()) {
                Thread {
                    try {
                        val loaded = EhRustBridge.loadTagDb(tagDb.absolutePath)
                        Log.i(TAG, "Auto-loaded tag database: $loaded (${tagDb.absolutePath})")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to auto-load tag database on startup", e)
                    }
                }.start()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load or init native library", e)
        }
    }

    /**
     * Register high-performance custom Coil ImageLoader:
     * 1. 25% heap MemoryCache + 256MB DiskCache for zero-lag smooth scrolling.
     * 2. OkHttp 64MB HTTP Cache.
     * 3. RustImageFetcher routes viewer page URLs directly through Rust LRU cache.
     * 4. EhCookieInterceptor injects session cookies without redundant Binder IPC.
     */
    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val httpCacheDir = File(context.cacheDir, "http_cache")
        val okHttpClient = OkHttpClient.Builder()
            .cache(Cache(httpCacheDir, 64L * 1024 * 1024))
            .addInterceptor(EhCookieInterceptor())
            .build()

        val diskCacheDir = context.cacheDir.resolve("image_cache")

        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDir.absolutePath.toPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .components {
                add(RustImageFetcher.Factory())
                add(RustImageFetcher.AndroidUriFactory())
                add(RustImageFetcher.StringFactory())
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .build()
    }
}

