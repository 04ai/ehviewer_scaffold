package com.example.ehviewer_scaffold.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.rust.GalleryItem
import kotlinx.coroutines.launch

/**
 * 全局应用设置管理器（基于 SharedPreferences，与 Rust 底层配置实时同步）
 */
object AppSettings {
    private const val PREF_NAME = "eh_app_settings"

    // ─── 站点设置 ─────────────────────────────────────────────────────────────
    const val KEY_IS_EX_HENTAI = "site_is_ex_hentai" // 默认 false: E-Hentai, true: ExHentai

    // ─── 显示设置 ─────────────────────────────────────────────────────────────
    const val KEY_SHOW_JAPANESE_TITLE = "show_japanese_title" // 优先显示日文原标题
    const val KEY_SHOW_TAG_TRANSLATIONS = "show_tag_translations" // 显示中文标签翻译

    // ─── 阅读设置 ─────────────────────────────────────────────────────────────
    const val KEY_READER_DIRECTION = "reader_direction" // 从右向左, 从左向右, 上下连续
    const val KEY_READER_FULLSCREEN = "reader_fullscreen" // 全屏阅读
    const val KEY_READER_SHOW_CLOCK = "reader_show_clock" // 显示系统时钟
    const val KEY_READER_SHOW_BATTERY = "reader_show_battery" // 显示电池电量
    const val KEY_READER_AUTO_PAGE = "reader_auto_page" // 自动翻页 (0 为关闭，或秒数)
    const val KEY_READER_PAGE_INTERVAL = "reader_page_interval" // 页面间隔 (px)
    const val KEY_READER_CUSTOM_BRIGHTNESS = "reader_custom_brightness" // 自定义亮度 (-1 跟随系统)

    // ─── 外观设置 (样式设置) ───────────────────────────────────────────────────
    const val KEY_THEME_FOLLOW_SYSTEM = "theme_follow_system" // 跟随系统深色模式
    const val KEY_THEME_AMOLED_BLACK = "theme_amoled_black" // 纯粹 AMOLED 黑
    const val KEY_THEME_PIXEL_SHIFT = "theme_pixel_shift" // 像素偏移 (防烧屏引擎)
    const val KEY_THEME_GLASSMORPHISM = "theme_glassmorphism" // 毛玻璃效果
    const val KEY_THEME_HAPTIC_FEEDBACK = "theme_haptic_feedback" // 触觉震动反馈
    const val KEY_THEME_COLOR = "theme_color" // 主题颜色 (纯净白, 经典绿, 暗夜黑, 樱花粉)
    const val KEY_LIST_MODE = "theme_list_mode" // 列表模式 (瀑布流, 列表卡片, 网格模式)

    // ─── 下载设置 ─────────────────────────────────────────────────────────────
    const val KEY_DOWNLOAD_CONCURRENCY = "download_concurrency" // 同时下载任务数 (默认 2)
    const val KEY_DOWNLOAD_DIR = "download_dir" // 自定义下载路径

    // ─── 搜索设置 ─────────────────────────────────────────────────────────────
    const val KEY_SEARCH_INCLUDE_NAME = "search_include_name" // 搜索时包含画廊名称
    const val KEY_SEARCH_INCLUDE_TAGS = "search_include_tags" // 搜索时包含标签
    const val KEY_SEARCH_INCLUDE_DESC = "search_include_desc" // 搜索时包含描述
    const val KEY_SEARCH_DEFAULT_CATEGORY = "search_default_category" // 默认搜索类别 (跟随主页)
    const val KEY_BLOCKED_TAGS = "blocked_tags" // 屏蔽标签集合

    // ─── 高级设置 ─────────────────────────────────────────────────────────────
    const val KEY_AUTO_CLEAN_EXPIRED = "auto_clean_expired" // 自动清理过期缓存
    const val KEY_AUTO_CLEAN_PERIOD_DAYS = "auto_clean_period_days" // 自动清理周期 (3, 7, 15, 30 天)

    // ─── 安全设置 ─────────────────────────────────────────────────────────────
    const val KEY_SECURITY_BIOMETRIC = "security_biometric" // 指纹/面容解锁
    const val KEY_SECURITY_BLUR_RECENT = "security_blur_recent" // 在最近任务中模糊界面

    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ─── 站点同步 ─────────────────────────────────────────────────────────────
    fun isExHentai(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_EX_HENTAI, false)
    }

    fun setExHentai(context: Context, isEx: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_EX_HENTAI, isEx).apply()
        val siteUrl = if (isEx) "https://exhentai.org" else "https://e-hentai.org"
        ioScope.launch {
            EhRustBridge.setSiteUrl(siteUrl)
        }
    }

    // ─── 显示设置 ─────────────────────────────────────────────────────────────
    fun isShowJapaneseTitle(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_JAPANESE_TITLE, true)
    }

    fun setShowJapaneseTitle(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_JAPANESE_TITLE, enabled).apply()
    }

    fun isShowTagTranslations(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_TAG_TRANSLATIONS, false)
    }

    fun setShowTagTranslations(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_TAG_TRANSLATIONS, enabled).apply()
    }

    // ─── 阅读设置 ─────────────────────────────────────────────────────────────
    fun getReaderDirection(context: Context): String {
        return getPrefs(context).getString(KEY_READER_DIRECTION, "从右向左") ?: "从右向左"
    }

    fun setReaderDirection(context: Context, dir: String) {
        getPrefs(context).edit().putString(KEY_READER_DIRECTION, dir).apply()
    }

    fun isReaderFullscreen(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_READER_FULLSCREEN, true)
    }

    fun setReaderFullscreen(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_READER_FULLSCREEN, enabled).apply()
    }

    fun isReaderShowClock(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_READER_SHOW_CLOCK, false)
    }

    fun setReaderShowClock(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_READER_SHOW_CLOCK, enabled).apply()
    }

    fun isReaderShowBattery(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_READER_SHOW_BATTERY, false)
    }

    fun setReaderShowBattery(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_READER_SHOW_BATTERY, enabled).apply()
    }

    fun getReaderAutoPage(context: Context): Int {
        return getPrefs(context).getInt(KEY_READER_AUTO_PAGE, 0)
    }

    fun setReaderAutoPage(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt(KEY_READER_AUTO_PAGE, seconds).apply()
    }

    fun getReaderPageInterval(context: Context): Int {
        return getPrefs(context).getInt(KEY_READER_PAGE_INTERVAL, 10)
    }

    fun setReaderPageInterval(context: Context, px: Int) {
        getPrefs(context).edit().putInt(KEY_READER_PAGE_INTERVAL, px).apply()
    }

    fun getReaderCustomBrightness(context: Context): Int {
        return getPrefs(context).getInt(KEY_READER_CUSTOM_BRIGHTNESS, -1)
    }

    fun setReaderCustomBrightness(context: Context, value: Int) {
        getPrefs(context).edit().putInt(KEY_READER_CUSTOM_BRIGHTNESS, value).apply()
    }

    // ─── 外观设置 ─────────────────────────────────────────────────────────────
    fun isThemeFollowSystem(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_THEME_FOLLOW_SYSTEM, false)
    }

    fun setThemeFollowSystem(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_THEME_FOLLOW_SYSTEM, enabled).apply()
    }

    fun isAmoledBlack(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_THEME_AMOLED_BLACK, false)
    }

    fun setAmoledBlack(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_THEME_AMOLED_BLACK, enabled).apply()
    }

    fun isPixelShift(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_THEME_PIXEL_SHIFT, true)
    }

    fun setPixelShift(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_THEME_PIXEL_SHIFT, enabled).apply()
    }

    fun isGlassmorphism(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_THEME_GLASSMORPHISM, true)
    }

    fun setGlassmorphism(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_THEME_GLASSMORPHISM, enabled).apply()
    }

    fun isHapticFeedback(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_THEME_HAPTIC_FEEDBACK, true)
    }

    fun setHapticFeedback(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_THEME_HAPTIC_FEEDBACK, enabled).apply()
    }

    fun getThemeColor(context: Context): String {
        return getPrefs(context).getString(KEY_THEME_COLOR, "经典绿") ?: "经典绿"
    }

    fun setThemeColor(context: Context, color: String) {
        getPrefs(context).edit().putString(KEY_THEME_COLOR, color).apply()
    }

    fun getListMode(context: Context): String {
        return getPrefs(context).getString(KEY_LIST_MODE, "瀑布流") ?: "瀑布流"
    }

    fun setListMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_LIST_MODE, mode).apply()
    }

    // ─── 下载设置 ─────────────────────────────────────────────────────────────
    fun getDownloadConcurrency(context: Context): Int {
        return getPrefs(context).getInt(KEY_DOWNLOAD_CONCURRENCY, 2)
    }

    fun setDownloadConcurrency(context: Context, n: Int) {
        getPrefs(context).edit().putInt(KEY_DOWNLOAD_CONCURRENCY, n).apply()
        ioScope.launch {
            EhRustBridge.setDownloadConcurrency(n)
        }
    }

    fun getDownloadDir(context: Context): String {
        val saved = getPrefs(context).getString(KEY_DOWNLOAD_DIR, "") ?: ""
        return if (saved.isNotEmpty()) saved else "${context.cacheDir.absolutePath}/eh_downloads"
    }

    fun setDownloadDir(context: Context, path: String) {
        getPrefs(context).edit().putString(KEY_DOWNLOAD_DIR, path).apply()
        ioScope.launch {
            EhRustBridge.setDownloadDir(path)
        }
    }

    // ─── 搜索设置 ─────────────────────────────────────────────────────────────
    fun isSearchIncludeName(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SEARCH_INCLUDE_NAME, true)
    }

    fun setSearchIncludeName(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SEARCH_INCLUDE_NAME, enabled).apply()
    }

    fun isSearchIncludeTags(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SEARCH_INCLUDE_TAGS, true)
    }

    fun setSearchIncludeTags(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SEARCH_INCLUDE_TAGS, enabled).apply()
    }

    fun isSearchIncludeDesc(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SEARCH_INCLUDE_DESC, true)
    }

    fun setSearchIncludeDesc(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SEARCH_INCLUDE_DESC, enabled).apply()
    }

    fun getDefaultSearchCategory(context: Context): String {
        return getPrefs(context).getString(KEY_SEARCH_DEFAULT_CATEGORY, "跟随主页") ?: "跟随主页"
    }

    fun setDefaultSearchCategory(context: Context, category: String) {
        getPrefs(context).edit().putString(KEY_SEARCH_DEFAULT_CATEGORY, category).apply()
    }

    fun getBlockedTags(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_BLOCKED_TAGS, emptySet()) ?: emptySet()
    }

    fun setBlockedTags(context: Context, tags: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_BLOCKED_TAGS, tags).apply()
        // Mirror to Rust on every change. Blocking a tag has to take effect on
        // the *next* listing fetch, and Rust is what filters listings (and what
        // appends the tags as `-ns:tag` exclusions when a query exists).
        pushBlockedTagsToRust(tags)
    }

    /**
     * Hand the block list to the Rust side. SharedPreferences stays
     * authoritative — this in-memory copy is replayed at cold start, exactly
     * like the site URL and the download directory.
     */
    private fun pushBlockedTagsToRust(tags: Set<String>) {
        val payload = try {
            EhRustBridge.json.encodeToString(tags.toList())
        } catch (_: Throwable) {
            return
        }
        ioScope.launch {
            try {
                EhRustBridge.setBlockedTags(payload)
                lastPushedBlockedTags = tags
            } catch (_: Throwable) {
                // Rust may not be loaded yet during very early startup; the cold
                // start replay in EhApplication covers that case.
            }
        }
    }

    /** Last list successfully handed to Rust, so a no-op push costs nothing. */
    @Volatile
    private var lastPushedBlockedTags: Set<String> = emptySet()

    /**
     * Push the block list to Rust **synchronously**, if it is not already there.
     *
     * For callers that need the Rust side to know the list before their next call.
     * The listing request uses it to decide whether an exclusion-only probe is
     * worthwhile, and on a cold start the Application's background push loses that
     * race — measured on device: the first listing was checked 192 ms before the
     * block list arrived, so the probe was skipped entirely.
     *
     * Must be called **off the main thread**: it crosses JNI.
     */
    fun pushBlockedTagsBlocking(context: Context) {
        val current = getBlockedTags(context)
        if (current == lastPushedBlockedTags) return
        val payload = try {
            EhRustBridge.json.encodeToString(current.toList())
        } catch (_: Throwable) {
            return
        }
        try {
            if (EhRustBridge.setBlockedTags(payload)) {
                lastPushedBlockedTags = current
            }
        } catch (_: Throwable) {
        }
    }

    fun addBlockedTag(context: Context, tag: String) {
        val current = getBlockedTags(context).toMutableSet()
        current.add(tag)
        setBlockedTags(context, current)
    }

    fun removeBlockedTag(context: Context, tag: String) {
        val current = getBlockedTags(context).toMutableSet()
        current.remove(tag)
        setBlockedTags(context, current)
    }

    // ─── 关注/订阅标签 ──────────────────────────────────────────────────────────
    private const val KEY_WATCHED_TAGS = "watched_tags_set"

    fun getWatchedTags(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_WATCHED_TAGS, emptySet()) ?: emptySet()
    }

    fun setWatchedTags(context: Context, tags: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_WATCHED_TAGS, tags).apply()
    }

    fun addWatchedTag(context: Context, tag: String) {
        val current = getWatchedTags(context).toMutableSet()
        current.add(tag)
        setWatchedTags(context, current)
    }

    fun removeWatchedTag(context: Context, tag: String) {
        val current = getWatchedTags(context).toMutableSet()
        current.remove(tag)
        setWatchedTags(context, current)
    }

    // ─── 高级设置 ─────────────────────────────────────────────────────────────
    fun isAutoCleanExpiredCache(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CLEAN_EXPIRED, true)
    }

    fun setAutoCleanExpiredCache(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CLEAN_EXPIRED, enabled).apply()
    }

    fun getAutoCleanPeriodDays(context: Context): Int {
        return getPrefs(context).getInt(KEY_AUTO_CLEAN_PERIOD_DAYS, 7)
    }

    fun setAutoCleanPeriodDays(context: Context, days: Int) {
        getPrefs(context).edit().putInt(KEY_AUTO_CLEAN_PERIOD_DAYS, days).apply()
    }

    // ─── 安全设置 ─────────────────────────────────────────────────────────────
    fun isBiometricUnlock(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SECURITY_BIOMETRIC, false)
    }

    fun setBiometricUnlock(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SECURITY_BIOMETRIC, enabled).apply()
    }

    fun isBlurInRecentTasks(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SECURITY_BLUR_RECENT, false)
    }

    fun setBlurInRecentTasks(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SECURITY_BLUR_RECENT, enabled).apply()
    }

    // ─── 浏览历史 ─────────────────────────────────────────────────────────────
    private const val KEY_HISTORY = "browse_history"
    /** Oldest entries drop off; 300 covers a long while without bloating the prefs. */
    private const val HISTORY_LIMIT = 300

    /**
     * Galleries opened on this device, most recent first.
     *
     * Recorded locally rather than read from E-Hentai's `/history`, which is gated
     * behind account perks and therefore empty for most users.
     */
    fun getHistory(context: Context): List<GalleryItem> {
        val raw = getPrefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            EhRustBridge.json.decodeFromString(raw)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Record a gallery as opened, moving it to the front if already present. */
    fun addHistory(context: Context, item: GalleryItem) {
        if (item.gid.isBlank() || item.token.isBlank()) return
        val current = getHistory(context).toMutableList()
        current.removeAll { it.gid == item.gid }
        current.add(0, item)
        val raw = try {
            EhRustBridge.json.encodeToString(current.take(HISTORY_LIMIT))
        } catch (_: Throwable) {
            return
        }
        getPrefs(context).edit().putString(KEY_HISTORY, raw).apply()
    }

    fun clearHistory(context: Context) {
        getPrefs(context).edit().remove(KEY_HISTORY).apply()
    }

    // ─── 会话 Cookie ──────────────────────────────────────────────────────────
    private const val KEY_SESSION_COOKIE = "session_cookie"

    /**
     * Mirror of the EH session cookie, so it can be replayed into Rust at cold start.
     *
     * Rust holds the session in memory only, so without a replay every restart loses it
     * and the signed-in pages (watched / favorites / history) answer "not logged in"
     * even though the login screen insists otherwise.
     *
     * The authoritative copy is still WebView's CookieManager — that is what the
     * image requests use. This mirror exists because touching `CookieManager` inside
     * `Application.onCreate` would force WebView to load on a background thread.
     */
    fun getSessionCookie(context: Context): String =
        getPrefs(context).getString(KEY_SESSION_COOKIE, "") ?: ""

    fun setSessionCookie(context: Context, cookie: String) {
        getPrefs(context).edit().putString(KEY_SESSION_COOKIE, cookie).apply()
    }

    // ─── 搜索历史 ─────────────────────────────────────────────────────────────
    private const val KEY_SEARCH_HISTORY = "search_history"
    private const val MAX_HISTORY = 20

    fun getSearchHistory(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_SEARCH_HISTORY, "") ?: ""
        return if (raw.isEmpty()) emptyList()
        else raw.split("\u0000").filter { it.isNotBlank() }
    }

    fun addSearchHistory(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val current = getSearchHistory(context).toMutableList()
        current.remove(q) // remove duplicate
        current.add(0, q) // insert at front
        val trimmed = current.take(MAX_HISTORY)
        getPrefs(context).edit().putString(KEY_SEARCH_HISTORY, trimmed.joinToString("\u0000")).apply()
    }

    fun clearSearchHistory(context: Context) {
        getPrefs(context).edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    // ─── 阅读进度 ─────────────────────────────────────────────────────────────
    // One int per gallery instead of a single serialised blob: reads and writes
    // are O(1) and never touch another gallery's entry. The trade-off is one
    // XML entry per gallery ever opened, which is fine at realistic sizes —
    // if it ever matters, sweep entries older than the oldest download instead
    // of rewriting the whole map on every page turn.
    private const val KEY_READ_PROGRESS_PREFIX = "read_progress_"

    /**
     * Last page the reader was left on, **0-based**; `-1` when this gallery has
     * never been opened. 0 is a legitimate value (page 1) and must not be read
     * as "no progress".
     */
    fun getReadingProgress(context: Context, gid: String): Int {
        if (gid.isEmpty()) return -1
        return getPrefs(context).getInt(KEY_READ_PROGRESS_PREFIX + gid, -1)
    }

    fun saveReadingProgress(context: Context, gid: String, page: Int) {
        if (gid.isEmpty() || page < 0) return
        getPrefs(context).edit().putInt(KEY_READ_PROGRESS_PREFIX + gid, page).apply()
    }

    fun clearReadingProgress(context: Context, gid: String) {
        if (gid.isEmpty()) return
        getPrefs(context).edit().remove(KEY_READ_PROGRESS_PREFIX + gid).apply()
    }
}

