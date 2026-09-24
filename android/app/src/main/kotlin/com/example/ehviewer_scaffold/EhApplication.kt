package com.example.ehviewer_scaffold

import android.app.Application
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.example.ehviewer_scaffold.coil.RustImageFetcher
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.ui.settings.AppSettings
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
            // Essential: CDN (ehgt.org / hath.network) blocks image hotlinking without appropriate Referer
            val referer = if (host.contains("exhentai.org") || cookieStr.contains("igneous=")) {
                "https://exhentai.org/"
            } else {
                "https://e-hentai.org/"
            }
            requestBuilder.header("Referer", referer)

            return chain.proceed(requestBuilder.build())
        }

        private fun getOrRefreshCookies(): String {
            if (sharedCookieString.isNotEmpty()) return sharedCookieString
            val now = System.currentTimeMillis()
            if (now - lastCookieCheckTime < 5000L) {
                return cachedCookieResult
            }
            lastCookieCheckTime = now
            val built = buildCookieStringFromManager()
            cachedCookieResult = built
            if (built.isNotEmpty()) {
                sharedCookieString = built
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

            val defaultUa = try {
                android.webkit.WebSettings.getDefaultUserAgent(this)
            } catch (e: Exception) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            }
            sharedUserAgent = defaultUa

            Thread {
                try {
                    val cachePath = cacheDir.absolutePath
                    val ok = EhRustBridge.initBackend(cachePath, false)
                    Log.i(TAG, "Rust backend init result: $ok (cacheDir=$cachePath)")

                    EhRustBridge.setUserAgent(defaultUa)
                    Log.i(TAG, "Synchronized system WebView User-Agent to Rust client: $defaultUa")

                    // ── Re-apply the settings Rust keeps only in memory ──────
                    // The site URL, the download directory and the download
                    // concurrency are plain in-process state on the Rust side.
                    // Without this block every cold start silently reverted to
                    // e-hentai.org / the default directory / the default
                    // concurrency while the settings screen kept showing the
                    // user's actual choices.
                    val siteUrl =
                        if (AppSettings.isExHentai(this@EhApplication)) "https://exhentai.org"
                        else "https://e-hentai.org"
                    EhRustBridge.setSiteUrl(siteUrl)
                    Log.i(TAG, "Restored site URL: $siteUrl")

                    // Also reloads the task queue stored inside that directory.
                    // Rust already defaults to <cacheDir>/eh_downloads, which is
                    // exactly what AppSettings.getDownloadDir() returns when the
                    // user has not picked a path, so the common case is a no-op.
                    val downloadDir = AppSettings.getDownloadDir(this@EhApplication)
                    EhRustBridge.setDownloadDir(downloadDir)
                    Log.i(TAG, "Restored download directory: $downloadDir")

                    val concurrency = AppSettings.getDownloadConcurrency(this@EhApplication)
                    EhRustBridge.setDownloadConcurrency(concurrency)
                    Log.i(TAG, "Restored download concurrency: $concurrency")

                    val tagDb = File(filesDir, "tag_db.json")
                    if (tagDb.exists()) {
                        val loaded = EhRustBridge.loadTagDb(tagDb.absolutePath)
                        Log.i(TAG, "Auto-loaded tag database: $loaded (${tagDb.absolutePath})")
                    }

                    // Blocked tags are in-memory on the Rust side too, and Rust is what
                    // filters listings for them. Synchronous here on purpose: the first
                    // listing request decides whether an exclusion-only probe is
                    // worthwhile, and it must not run before this has landed.
                    AppSettings.pushBlockedTagsBlocking(this@EhApplication)
                    Log.i(
                        TAG,
                        "Restored blocked tags: ${AppSettings.getBlockedTags(this@EhApplication).size}"
                    )

                    // Session cookie: also in-memory on the Rust side. A restart loses it,
                    // and then every page that needs an account (watched / favorites /
                    // history) answers "not logged in" while the login screen insists the
                    // cookie was saved. Replayed from the mirror in AppSettings — reading
                    // WebView's CookieManager here would load WebView on this thread.
                    var session = AppSettings.getSessionCookie(this@EhApplication)
                    if (session.isBlank()) {
                        // First start with a build that has this mirror: older builds never
                        // wrote it, so recover the session from WebView's cookie store
                        // instead of asking the user to log in again.
                        session = try {
                            val cm = CookieManager.getInstance()
                            val eh = cm.getCookie("https://e-hentai.org").orEmpty()
                            val ex = cm.getCookie("https://exhentai.org").orEmpty()
                            listOf(eh, ex).filter { it.isNotBlank() }.joinToString("; ")
                        } catch (e: Throwable) {
                            Log.w(TAG, "Could not read WebView cookies for migration", e)
                            ""
                        }
                        if (session.isNotBlank()) {
                            AppSettings.setSessionCookie(this@EhApplication, session)
                            Log.i(TAG, "Migrated session cookie from the WebView store")
                        }
                    }
                    if (session.isNotBlank()) {
                        EhRustBridge.syncCookies(session)
                        sharedCookieString = session
                        Log.i(TAG, "Restored session cookie (${session.length} chars)")
                    } else {
                        Log.i(TAG, "No session cookie available")
                    }

                    // Expired-cache sweeping used to run only when the user
                    // opened the Advanced settings screen, so the "auto clean"
                    // switch did nothing on its own. Sweep once per cold start.
                    if (AppSettings.isAutoCleanExpiredCache(this@EhApplication)) {
                        val days = AppSettings.getAutoCleanPeriodDays(this@EhApplication)
                        val freed = EhRustBridge.clearExpiredCache(days)
                        if (freed > 0) {
                            Log.i(TAG, "Auto-cleaned ${freed / 1024 / 1024} MB of expired cache (>$days days)")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Background native backend initialization failed", e)
                }
            }.start()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    /**
     * Register high-performance custom Coil ImageLoader:
     * 1. 25% heap MemoryCache + 256MB DiskCache for zero-lag smooth scrolling.
     * 2. OkHttp with generous per-host concurrency (see note below).
     * 3. RustImageFetcher routes viewer page URLs directly through Rust LRU cache.
     * 4. EhCookieInterceptor injects session cookies without redundant Binder IPC.
     */
    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val dispatcher = okhttp3.Dispatcher().apply {
            // E-Hentai serves *all* list and detail thumbnails from a single CDN
            // host (ehgt.org / *.hath.network). The previous 32-per-host cap was
            // therefore the real ceiling on effective parallelism, not maxRequests —
            // a waterfall screen can have 60+ thumbs in flight, so the surplus just
            // queued and users saw tiles sitting blank while lower ones had already
            // loaded. Raising the per-host limit lets the whole visible screen
            // resolve together.
            //
            // Note this is far more than the JNI gate's width (see
            // EhRustBridge.withJniSlot): Coil requests that hit the Rust reader
            // path block on a JNI slot, while plain CDN thumbnails do not, so the
            // two ceilings are intentionally different.
            maxRequests = 128
            maxRequestsPerHost = 96
        }
        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(64, 5, java.util.concurrent.TimeUnit.MINUTES))
            // No OkHttp `Cache` on purpose.
            //
            // Coil's own DiskCache below already persists every fetched body, so
            // an HTTP cache here stored the *same bytes a second time* — two
            // copies of every thumbnail. With the Rust image cache (512 MB) that
            // made three disk layers holding overlapping data. Dropping this one
            // removes the duplicate and costs nothing: Coil's DiskCache still
            // serves repeat loads, and the Rust layer covers reader pages.
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
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(RustImageFetcher.Factory())
                add(RustImageFetcher.AndroidUriFactory())
                add(RustImageFetcher.StringFactory())
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .build()
    }
}

