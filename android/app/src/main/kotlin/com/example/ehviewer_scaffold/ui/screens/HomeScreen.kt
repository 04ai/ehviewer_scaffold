package com.example.ehviewer_scaffold.ui.screens

import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import com.example.ehviewer_scaffold.data.HomeStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.rust.GalleryItem
import com.example.ehviewer_scaffold.rust.SearchOptions
import com.example.ehviewer_scaffold.rust.TagSuggestion
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.ehviewer_scaffold.utils.HapticFeedbackManager
import com.example.ehviewer_scaffold.utils.SquircleShape
import com.example.ehviewer_scaffold.utils.appleCardClickable
import com.example.ehviewer_scaffold.utils.LocalAnimatedVisibilityScope
import com.example.ehviewer_scaffold.utils.LocalSharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import com.example.ehviewer_scaffold.ui.theme.Physics

/**
 * Page size E-Hentai returns. Fewer items than this means the listing is exhausted.
 */
private const val PAGE_SIZE_HINT = 20

/**
 * Minimum time the pull-to-refresh indicator stays up. A refresh that completes in
 * ~80 ms makes the indicator blink once, which reads as "nothing happened" — this
 * floor is purely so the action is visible, and costs nothing when the request is
 * slower than it.
 */
private const val MIN_REFRESH_INDICATOR_MS = 450L

// ── E-Hentai category bitmask ────────────────────────────────────────────────
//
// `f_cats` is an **exclusion** mask: each bit that is set removes that category
// from the result. "Show only Doujinshi" therefore means CATS_ALL with the
// Doujinshi bit cleared. Bit values are E-Hentai's own (the advanced-search
// checkbox order: Misc, Doujinshi, Manga, Artist CG, Game CG, Western, Non-H,
// Image Set, Cosplay, Asian Porn).
private const val CATS_ALL = 0x3FF
private const val CAT_DOUJINSHI = 0x2
private const val CAT_MANGA = 0x4
private const val CAT_GAME_CG = 0x10
private const val CAT_WESTERN = 0x20
private const val CAT_NON_H = 0x40
private const val CAT_COSPLAY = 0x100

/**
 * Translate the 「默认搜索类别」 setting into an `f_cats` exclusion mask.
 *
 * Returns `null` for 「跟随主页」, meaning "send no f_cats at all" — the previous
 * behaviour for every value, which is why the setting never had any effect.
 */
private fun searchCategoryExclusionMask(label: String): Int? = when (label) {
    "全部" -> 0
    "同人志" -> CATS_ALL and CAT_DOUJINSHI.inv()
    "漫画" -> CATS_ALL and CAT_MANGA.inv()
    "游戏CG" -> CATS_ALL and CAT_GAME_CG.inv()
    "欧美" -> CATS_ALL and CAT_WESTERN.inv()
    // 「原创」 is this app's label for Non-H works, matching E-Hentai's own
    // Non-H category.
    "原创" -> CATS_ALL and CAT_NON_H.inv()
    "动漫Cosplay" -> CATS_ALL and CAT_COSPLAY.inv()
    else -> null // 跟随主页
}

/** E-Hentai uses at most the first 8 terms of a search; extras are ignored. */
private const val EH_MAX_SEARCH_TERMS = 8

/**
 * Append the blocked tags to a search query as E-Hentai exclusions.
 *
 * This is the half of blocked-tag filtering that is *complete*: the server
 * applies it to the whole result set, not just the galleries this device has
 * already seen. Two rules come straight from E-Hentai's search syntax:
 *
 *  - "Searches with only exclusions are not permitted" — so nothing is appended
 *    when the user has not typed a query. Browsing without a query falls back to
 *    client-side filtering against the Rust gallery→tags index.
 *  - Only the first 8 terms are used, so the append is capped by whatever budget
 *    the user's own terms leave.
 */
private fun appendBlockedExclusions(query: String?, blocked: Collection<String>): String? {
    if (blocked.isEmpty()) return query
    val typed = query?.trim().orEmpty()
    if (typed.isEmpty()) return query
    val used = typed.split(Regex("\\s+")).count { it.isNotBlank() }
    val budget = EH_MAX_SEARCH_TERMS - used
    if (budget <= 0) return query
    val exclusions = blocked.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { if (it.contains(' ')) "-\"$it\"" else "-$it" }
        .take(budget)
        .toList()
    if (exclusions.isEmpty()) return query
    return typed + " " + exclusions.joinToString(" ")
}

/**
 * 抽屉菜单项枚举：与 EH 网站云端特性对应
 */
enum class DrawerNavCategory(
    val title: String,
    val icon: ImageVector,
    /** Server path; null means this entry is not a listing at all (downloads, settings). */
    val cloudPath: String?,
    /**
     * Served from data recorded on this device instead of a request.
     *
     * History uses it because E-Hentai's own /history is gated behind account perks,
     * so a cloud-backed list is empty for most people — while "what did I open" is
     * something the app can always answer.
     */
    val isLocal: Boolean = false
) {
    HOME("主页", Icons.Default.Home, ""),
    WATCHED("订阅", Icons.Default.Subscriptions, "watched"),
    POPULAR("热门", Icons.Default.Whatshot, "popular"),
    TOPLIST("排行榜", Icons.Default.Leaderboard, "toplist.php"),
    FAVORITES("收藏", Icons.Default.Favorite, "favorites.php"),
    HISTORY("历史", Icons.Default.History, null, isLocal = true),
    DOWNLOADS("下载", Icons.Default.Download, null),
    SETTINGS("设置", Icons.Default.Settings, null)
}

/**
 * 获取当前已登录用户的 ipb_member_id (UID)
 */
fun getEhUid(): String? {
    return try {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie("https://e-hentai.org") ?: ""
        val c2 = cm.getCookie("https://forums.e-hentai.org") ?: ""
        val c3 = cm.getCookie("https://exhentai.org") ?: ""
        val combined = "$c1; $c2; $c3"
        val regex = "(?:^|;\\s*)ipb_member_id=([^;\\s]+)".toRegex()
        val match = regex.find(combined)
        val uid = match?.groupValues?.getOrNull(1)?.trim()
        if (!uid.isNullOrEmpty() && uid != "0") uid else null
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onGalleryClick: (gid: String, token: String, coverUrl: String) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogin: () -> Unit,
    initialQuery: String? = null,   // pre-populate from tag search in detail page
    onBack: (() -> Unit)? = null,   // navigate back to previous screen (e.g. GalleryDetailScreen)
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // ── Playback gate ─────────────────────────────────────────────────────────
    // NavHost keeps covered entries in the composition, and during a navigation
    // transition both the outgoing and incoming screens are live at once. Reading
    // the Lifecycle lets a covered HomeScreen notice that it is no longer the
    // foreground destination and stop paging / clearing focus. The flag starts
    // false so a screen that mounts hidden does not immediately kick off a fetch.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isPlaybackActive by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isPlaybackActive = true
            try {
                awaitCancellation()
            } finally {
                isPlaybackActive = false
            }
        }
    }

    // Register hardware/gesture back handler if onBack callback is provided
    if (onBack != null) {
        BackHandler { onBack() }
    }

    var selectedCategory by remember {
        mutableStateOf(if (initialQuery.isNullOrBlank()) HomeStateHolder.selectedCategory else DrawerNavCategory.HOME)
    }
    var memberUid by remember { mutableStateOf<String?>(null) }

    // Growable list: new pages are appended directly without full-list replacement.
    val galleryItems = if (initialQuery.isNullOrBlank()) {
        HomeStateHolder.galleryItems
    } else {
        remember { mutableStateListOf<GalleryItem>() }
    }

    // ── Blocked-tag pruning ───────────────────────────────────────────────────
    //
    // A tag blocked from a detail page (long-press → 屏蔽此标签) has to affect the
    // list the user is returning to, not just the next fetch. Pruning in place
    // rather than reloading keeps the scroll position, and Rust does the matching
    // against its gallery→tags index in one call for the whole list rather than
    // one call per item.
    //
    // Lives here rather than next to the playback gate above because it needs
    // `galleryItems`, which is declared just above.
    var blockListSnapshot by remember { mutableStateOf(AppSettings.getBlockedTags(context)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val currentBlocked = AppSettings.getBlockedTags(context)
            if (currentBlocked != blockListSnapshot) {
                blockListSnapshot = currentBlocked
                val gids = galleryItems.map { it.gid }
                if (gids.isNotEmpty()) {
                    val kept = try {
                        EhRustBridge.keepUnblockedGalleryIds(gids)
                    } catch (_: Exception) {
                        gids // never empty the list because a call failed
                    }
                    if (kept.size != gids.size) {
                        val keepSet = kept.toHashSet()
                        galleryItems.removeAll { it.gid !in keepSet }
                    }
                }
            }
        }
    }

    // Login state must be re-read on resume. Coming back from the login screen does not
    // recreate this screen (it is the bottom of the back stack), so without this the
    // drawer would keep saying "not logged in" immediately after a successful login.
    // CookieManager access goes off the main thread — it is binder IPC.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            memberUid = withContext(Dispatchers.IO) { getEhUid() }
        }
    }

    var isLoading by remember {
        mutableStateOf(if (initialQuery.isNullOrBlank()) (!HomeStateHolder.isInitialized || galleryItems.isEmpty()) else true)
    }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var hasMore by remember {
        mutableStateOf(if (initialQuery.isNullOrBlank()) HomeStateHolder.hasMore else true)
    }
    var currentPage by remember {
        mutableIntStateOf(if (initialQuery.isNullOrBlank()) HomeStateHolder.currentPage else 0)
    }
    var currentQuery by remember {
        mutableStateOf<String?>(if (initialQuery.isNullOrBlank()) HomeStateHolder.currentQuery else initialQuery)
    }
    var currentNextUrl by remember {
        mutableStateOf<String?>(if (initialQuery.isNullOrBlank()) HomeStateHolder.currentNextUrl else null)
    }
    var searchQuery by remember {
        mutableStateOf(initialQuery ?: (HomeStateHolder.currentQuery ?: ""))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ── Search suggestion & history state ────────────────────────────────────
    var tagSuggestions by remember { mutableStateOf<List<TagSuggestion>>(emptyList()) }
    var searchHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearchFocused by remember { mutableStateOf(false) }
    var suggestionJob by remember { mutableStateOf<Job?>(null) }

    // load history when composable enters
    LaunchedEffect(Unit) {
        searchHistory = AppSettings.getSearchHistory(context)
    }

    // Close the dropdown **without** discarding what was typed — same outcome as
    // tapping outside the field, which only ever dropped focus. Extracted rather
    // than repeated because it now has three separate triggers (scroll, drag on
    // the panel, back key) that must stay behaviourally identical.
    fun dismissSuggestions() {
        isSearchFocused = false
        tagSuggestions = emptyList()
        suggestionJob?.cancel()
        focusManager.clearFocus()
    }

    // Scroll position is saved, not just remembered: coming back from a long
    // read (or a config change, or a process restart) then lands where you left
    // off instead of at the top of a reloaded list.
    //
    // `scrollResetToken` is bumped whenever the list is *replaced* (refresh, search,
    // category switch), which recreates both states. That is the only dependable way
    // back to the first item: scrolling a state that still holds an offset from a
    // longer list — index 20 of a list that just became 10 items — leaves it clamped
    // near the end, which is exactly the "it did not follow to the first gallery"
    // symptom. Coming back from a detail page does *not* bump it, so the position you
    // left is still restored.
    var scrollResetToken by remember { mutableIntStateOf(0) }
    val gridState = rememberSaveable(scrollResetToken, saver = LazyStaggeredGridState.Saver) { LazyStaggeredGridState() }
    val listState = rememberSaveable(scrollResetToken, saver = LazyListState.Saver) { LazyListState() }

    // Scrolling the results must dismiss the dropdown. Before this, `swipe` did
    // nothing because the field never lost focus — the only way out was to tap
    // somewhere else first, which reads as "stuck" while scrolling with one hand.
    //
    // Observed with snapshotFlow rather than `derivedStateOf` on purpose: reading
    // `isScrollInProgress` *in composition* flips this whole screen to invalid at
    // the exact instant a drag begins, and recomposing a screen this large costs
    // a frame — which is precisely the "it stops tracking my finger" feeling.
    // snapshotFlow watches the same state from outside composition, so nothing
    // recomposes.
    // `scrollResetToken` is a key here and in the load-more observer below: scrolling
    // states are *recreated* when it changes, so an effect that captured the old ones
    // keeps watching objects that are no longer attached to anything on screen.
    LaunchedEffect(scrollResetToken) {
        snapshotFlow { gridState.isScrollInProgress || listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && isSearchFocused) {
                    dismissSuggestions()
                }
            }
    }

    // Back closes the suggestions first, before it navigates away — otherwise the
    // first press leaves the screen even though the user only wanted the panel gone.
    BackHandler(enabled = isSearchFocused) { dismissSuggestions() }

    // Read the list-mode preference from AppSettings.
    var listMode by remember { mutableStateOf(AppSettings.getListMode(context)) }

    // 触觉反馈开关与管理器
    val isHapticEnabled = remember { AppSettings.isHapticFeedback(context) }
    val haptic = remember { HapticFeedbackManager(context) }
    // 毛玻璃效果开关
    val isGlassmorphism = remember { AppSettings.isGlassmorphism(context) }

    // Build SearchOptions from user's search settings
    fun buildSearchOptions() = SearchOptions(
        fSname = AppSettings.isSearchIncludeName(context),
        fStags = AppSettings.isSearchIncludeTags(context),
        fSdesc = AppSettings.isSearchIncludeDesc(context),
        // Was hard-coded to null, which is why the 「默认搜索类别」 setting did
        // nothing. Re-read on every call instead of caching it in a `remember`:
        // this screen is not rebuilt when the user returns from the settings
        // sub-screen, so a cached mask would keep the old value until restart.
        fCats = searchCategoryExclusionMask(AppSettings.getDefaultSearchCategory(context))
    )

    /**
     * The query actually sent to Rust: the user's text plus the blocked tags as
     * exclusions. Kept separate from `currentQuery`, which is what the search
     * field displays — the exclusions must never show up in the input box or be
     * re-parsed as user input.
     */
    fun effectiveQuery(raw: String?): String? =
        appendBlockedExclusions(raw, AppSettings.getBlockedTags(context))
    LaunchedEffect(Unit) { listMode = AppSettings.getListMode(context) }

    // ── Infinite Scroll Trigger (Seamless Prefetching before hitting bottom) ───
    // Use snapshotFlow to continuously observe scroll position. The old
    // LaunchedEffect(shouldLoadMore) approach only fired when the derived boolean
    // *changed* value, so after the first load-more the effect would never
    // re-trigger if the user was still near the bottom.
    //
    // Gated on isPlaybackActive: a HomeScreen that has been covered by a newer
    // destination must not keep paging in the background. Without this guard,
    // stacked search screens competed for the shared OkHttp connection pool and
    // the visible list appeared to jump between unrelated result sets.
    // `scrollResetToken` must be a key: `scrollListToTop` recreates the scroll states,
    // and without it this effect would keep observing a discarded state — which is
    // exactly how "load more on scroll" stopped firing after the first page.
    LaunchedEffect(listMode, selectedCategory, hasMore, scrollResetToken) {
        snapshotFlow {
            val lastVisible = if (listMode == "列表卡片") {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            } else {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            }
            val total = if (listMode == "列表卡片") {
                listState.layoutInfo.totalItemsCount
            } else {
                gridState.layoutInfo.totalItemsCount
            }
            total > 0 && lastVisible >= total - 8
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (!nearEnd || isLoadingMore || !hasMore || isLoading || !isPlaybackActive) return@collect
                isLoadingMore = true
                try {
                    val nextPage = currentPage + 1
                    val path = selectedCategory.cloudPath ?: ""
                    val opts = buildSearchOptions()
                    val result = withContext(Dispatchers.IO) {
                        AppSettings.pushBlockedTagsBlocking(context)
                        if (path.isEmpty()) {
                            EhRustBridge.getGalleryList(page = nextPage, pageUrl = currentNextUrl, query = effectiveQuery(currentQuery), options = opts)
                        } else {
                            EhRustBridge.getCustomList(path = path, page = nextPage, pageUrl = currentNextUrl, query = effectiveQuery(currentQuery), options = opts)
                        }
                    }
                    if (result.items.isEmpty()) {
                        hasMore = false
                        if (initialQuery.isNullOrBlank()) HomeStateHolder.hasMore = false
                    } else {
                        val existingGids = galleryItems.map { it.gid }.toSet()
                        val newItems = result.items.filter { it.gid !in existingGids }
                        if (newItems.isNotEmpty()) {
                            galleryItems.addAll(newItems)
                            currentPage = nextPage
                            currentNextUrl = result.nextUrl
                            if (initialQuery.isNullOrBlank()) {
                                HomeStateHolder.currentPage = nextPage
                                HomeStateHolder.currentNextUrl = result.nextUrl
                            }
                        } else {
                            hasMore = false
                            if (initialQuery.isNullOrBlank()) HomeStateHolder.hasMore = false
                        }
                    }
                } catch (_: Exception) {
                    // Silent: network errors on append are non-fatal; user can scroll up to retry
                } finally {
                    isLoadingMore = false
                }
            }
    }

    /**
     * Put a **replaced** list back at its first item.
     *
     * Bumps [scrollResetToken], which recreates the scroll states. Scrolling instead
     * does not work here: the state is still holding an offset from the longer list,
     * and once the content shrinks the grid clamps that offset near the new end, so
     * the list settles beside the last gallery rather than the first.
     */
    fun scrollListToTop() {
        scrollResetToken++
    }

    // ── Initial / Search Load ─────────────────────────────────────────────────
    fun loadGalleries(category: DrawerNavCategory, query: String? = null, isFullReset: Boolean = false) {
        // Raised synchronously, *outside* the coroutine. Both of this screen's
        // initial-load LaunchedEffects guard on `isLoading`, but the flag used
        // to be set as the coroutine's first statement — i.e. only once the
        // dispatcher ran it, which gave the second effect a window to observe
        // `isLoading == false` and fire a duplicate initial request.
        isLoading = true
        scope.launch {
            // Removed blocking scrollToItem(0) here to prevent infinite suspension on empty lists
            hasMore = true
            currentPage = 0
            currentQuery = query?.trim()?.ifBlank { null }
            errorMessage = null
            if (isFullReset) {
                galleryItems.clear()
            }
            if (initialQuery.isNullOrBlank()) {
                HomeStateHolder.currentPage = 0
                HomeStateHolder.currentQuery = currentQuery
                HomeStateHolder.hasMore = true
                HomeStateHolder.selectedCategory = category
            }
            try {
                // Local sources are answered from what this device recorded — no request
                // at all, and nothing to paginate.
                if (category.isLocal) {
                    val all = AppSettings.getHistory(context)
                    val q = currentQuery
                    galleryItems.clear()
                    galleryItems.addAll(
                        if (q.isNullOrBlank()) {
                            all
                        } else {
                            all.filter { it.title.contains(q, ignoreCase = true) }
                        }
                    )
                    hasMore = false
                    currentPage = 0
                    currentNextUrl = null
                    isLoading = false
                    return@launch
                }

                val path = category.cloudPath ?: ""
                val opts = buildSearchOptions()
                val result = withContext(Dispatchers.IO) {
                    // Rust decides whether an exclusion-only listing probe is worthwhile,
                    // so it has to know the block list already. On a cold start the
                    // Application's background push loses the race to this very request
                    // (measured: checked 192 ms before the list arrived, so the probe was
                    // skipped). No-op when nothing changed.
                    AppSettings.pushBlockedTagsBlocking(context)
                    if (path.isEmpty()) {
                        EhRustBridge.getGalleryList(page = 0, query = effectiveQuery(currentQuery), options = opts)
                    } else {
                        EhRustBridge.getCustomList(path = path, page = 0, query = effectiveQuery(currentQuery), options = opts)
                    }
                }
                currentNextUrl = result.nextUrl
                if (initialQuery.isNullOrBlank()) {
                    HomeStateHolder.currentNextUrl = result.nextUrl
                }
                android.util.Log.d("HomeScreen", "loadGalleries result: count=${result.items.size}, nextUrl=${result.nextUrl}")
                if (result.items.isNotEmpty()) {
                    if (isFullReset) {
                        galleryItems.addAll(result.items)
                        scrollListToTop()
                    } else {
                        val existingGids = galleryItems.map { it.gid }.toSet()
                        val newItems = result.items.filter { it.gid !in existingGids }
                        galleryItems.addAll(0, newItems)
                    }
                } else if (galleryItems.isEmpty()) {
                    errorMessage = "未能获取画廊列表，请检查网络连接或Cookie登录状态"
                }
                if (initialQuery.isNullOrBlank()) {
                    HomeStateHolder.isInitialized = true
                }
                if (result.items.size < PAGE_SIZE_HINT) {
                    hasMore = false
                    if (initialQuery.isNullOrBlank()) {
                        HomeStateHolder.hasMore = false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "loadGalleries exception", e)
                if (galleryItems.isEmpty()) {
                    errorMessage = e.localizedMessage ?: "加载失败，请检查网络或登录状态"
                }
            } finally {
                isLoading = false
            }
        }
    }

    // Standard pull-to-refresh: refetch page 0 and replace the list.
    //
    // Two things here are not obvious:
    //  - `forceRefresh = true` bypasses the listing's disk cache. Without it a pull
    //    inside the 300s TTL was served from cache in a few milliseconds: the list
    //    came back identical and nothing on screen changed.
    //  - The indicator is held up for a minimum time. Even a genuine network refresh
    //    can finish in ~100ms on a warm connection, and an indicator that blinks that
    //    fast reads as "nothing happened".
    fun refreshGalleries() {
        scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                if (selectedCategory.isLocal) {
                    // Nothing to fetch — re-read what this device recorded.
                    galleryItems.clear()
                    galleryItems.addAll(AppSettings.getHistory(context))
                    currentPage = 0
                    currentNextUrl = null
                    hasMore = false
                    scrollListToTop()
                    return@launch
                }

                val path = selectedCategory.cloudPath ?: ""
                val opts = buildSearchOptions()
                val result = withContext(Dispatchers.IO) {
                    AppSettings.pushBlockedTagsBlocking(context)
                    if (path.isEmpty()) {
                        EhRustBridge.getGalleryList(
                            page = 0,
                            query = effectiveQuery(currentQuery),
                            options = opts,
                            forceRefresh = true
                        )
                    } else {
                        EhRustBridge.getCustomList(
                            path = path,
                            page = 0,
                            query = effectiveQuery(currentQuery),
                            options = opts,
                            forceRefresh = true
                        )
                    }
                }
                // Only replace when the server actually gave us something: wiping a
                // good list because a refresh came back empty is worse than keeping it.
                if (result.items.isNotEmpty() || galleryItems.isEmpty()) {
                    galleryItems.clear()
                    galleryItems.addAll(result.items)
                    currentPage = 0
                    currentNextUrl = result.nextUrl
                    hasMore = result.items.size >= PAGE_SIZE_HINT
                    if (initialQuery.isNullOrBlank()) {
                        HomeStateHolder.currentPage = 0
                        HomeStateHolder.currentNextUrl = result.nextUrl
                        HomeStateHolder.hasMore = hasMore
                    }
                    scrollListToTop()
                }
            } catch (_: Exception) {
            } finally {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < MIN_REFRESH_INDICATOR_MS) {
                    delay(MIN_REFRESH_INDICATOR_MS - elapsed)
                }
                isRefreshing = false
            }
        }
    }

    fun refreshUid() { memberUid = getEhUid() }

    LaunchedEffect(Unit) {
        refreshUid()
        if (!initialQuery.isNullOrBlank()) {
            loadGalleries(DrawerNavCategory.HOME, initialQuery, isFullReset = true)
        } else if (!HomeStateHolder.isInitialized || galleryItems.isEmpty()) {
            loadGalleries(HomeStateHolder.selectedCategory, HomeStateHolder.currentQuery, isFullReset = true)
        }
    }

    // Drop the soft-keyboard focus and close the suggestion sheet as soon as the
    // screen is covered. A covered screen that keeps focus can keep the IME
    // fighting with the incoming destination over the adjustResize window insets,
    // which is what made tag-to-tag navigation feel like it was stuttering.
    LaunchedEffect(isPlaybackActive) {
        if (!isPlaybackActive) {
            isSearchFocused = false
            focusManager.clearFocus()
        }
    }

    // Recover from a load that never ran because this screen mounted while hidden.
    // `isPlaybackActive` only flips true once the entry is RESUMED, so a screen that
    // was created during a transition (or covered immediately) can reach here with
    // an empty list and no in-flight request — which previously left the grid stuck
    // on a blank loading state. Kick the fetch once we actually become visible.
    LaunchedEffect(isPlaybackActive) {
        if (!isPlaybackActive) return@LaunchedEffect
        if (galleryItems.isNotEmpty() || isLoading) return@LaunchedEffect
        if (!initialQuery.isNullOrBlank()) {
            loadGalleries(DrawerNavCategory.HOME, initialQuery, isFullReset = true)
        } else if (!HomeStateHolder.isInitialized) {
            loadGalleries(HomeStateHolder.selectedCategory, HomeStateHolder.currentQuery, isFullReset = true)
        }
    }

    // ── Drawer + Scaffold ─────────────────────────────────────────────────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        // On search-result subscreens (onBack != null) the leading icon is a back
        // arrow, not a menu button — the edge swipe is the ONLY way the drawer
        // could open there. That edge swipe is also MIUI's back gesture, and both
        // fight over the same left-edge rightward drag, so back has to win here.
        // (The drawer has no other entry point on those screens anyway.)
        gesturesEnabled = onBack == null,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                // Zero width on search-result subscreens: the sheet is laid out
                // off-screen (negative offset) while closed, and this destination
                // enters/exits with a *full screen width* slide — which drags that
                // off-screen panel into view for the duration of the transition.
                // It is unreachable there anyway (back arrow + gestures off).
                modifier = Modifier.width(if (onBack == null) 300.dp else 0.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                            onNavigateToLogin()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 22.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00796B))
                        ) {
                            Text(
                                text = if (memberUid != null) "U" else "?",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (memberUid != null) "UID: $memberUid" else "未登录",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (memberUid != null) "点击管理账号" else "点击使用 Cookie 登录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = "账号管理",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(10.dp))

                DrawerNavCategory.entries.forEach { item ->
                    val isSelected = selectedCategory == item
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = {
                            Text(
                                text = item.title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        badge = {
                            if (item == DrawerNavCategory.HOME && isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "过滤设置",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        selected = isSelected,
                        shape = RoundedCornerShape(12.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            unselectedContainerColor = Color.Transparent
                        ),
                        onClick = {
                            when (item) {
                                DrawerNavCategory.DOWNLOADS -> {
                                    scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                                    onNavigateToDownloads()
                                }
                                DrawerNavCategory.SETTINGS -> {
                                    scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                                    onNavigateToSettings()
                                }
                                else -> {
                                    scope.launch {
                                        drawerState.close()
                                        selectedCategory = item
                                        searchQuery = ""
                                        loadGalleries(item, null, isFullReset = true)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        },
        // Clip to bounds *only where it is needed*: the closed drawer sheet is
        // parked at a negative x offset, and a transition that slides this whole
        // screen (tag_search enters/exits by a full screen width) drags it back
        // into view — that was the drawer "flashing" over the detail page.
        //
        // The main home screen never gets a full-width slide (its transitions are
        // ±1/3 of the screen, which keeps the sheet off-screen anyway), so it
        // keeps no clip layer under its scrollable grid.
        modifier = if (onBack == null) modifier else modifier.clipToBounds(),
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                // ── Search bar + suggestion dropdown (floating) ────────────────────
                val matchingHistory = if (searchQuery.isNotBlank()) {
                    searchHistory.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
                } else {
                    searchHistory
                }
                val showDropdown = isSearchFocused && (tagSuggestions.isNotEmpty() || matchingHistory.isNotEmpty())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Column {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = if (isGlassmorphism) 0.85f else 1f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .padding(horizontal = 6.dp)
                            ) {
                                IconButton(onClick = {
                                    if (onBack != null) {
                                        onBack()
                                    } else {
                                        refreshUid()
                                        scope.launch { drawerState.open() }
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (onBack != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                                        contentDescription = if (onBack != null) "返回" else "打开抽屉栏",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty() && !isSearchFocused) {
                                        val hintText = when (selectedCategory) {
                                            DrawerNavCategory.HOME -> "搜索画廊、标签..."
                                            DrawerNavCategory.WATCHED -> "搜索我的订阅..."
                                            DrawerNavCategory.POPULAR -> "热门画廊"
                                            DrawerNavCategory.TOPLIST -> "排行榜"
                                            DrawerNavCategory.FAVORITES -> "搜索收藏..."
                                            DrawerNavCategory.HISTORY -> "历史记录"
                                            else -> "搜索..."
                                        }
                                        Text(
                                            text = hintText,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { q ->
                                            searchQuery = q
                                            // Debounce: cancel previous job and launch a new suggestion fetch
                                            suggestionJob?.cancel()
                                            suggestionJob = scope.launch {
                                                delay(220)
                                                if (q.isBlank()) {
                                                    tagSuggestions = emptyList()
                                                    return@launch
                                                }

                                                // 1. Local, synchronous-looking scan: the translation database
                                                //    *plus* every tag we've seen on a gallery you opened. This
                                                //    is what makes English completion work without downloading
                                                //    anything — it never touches the network.
                                                val trimmed: String = q.trim()
                                                val local = try {
                                                    withContext(Dispatchers.IO) { EhRustBridge.searchTags(trimmed) }
                                                } catch (_: Throwable) { emptyList() }
                                                if (!isActive) return@launch
                                                tagSuggestions = local

                                                // 2. E-Hentai's own completion endpoint. Covers the long tail of
                                                //    tags the curated translation database never includes, with
                                                //    no 10 MB download. Offline it just returns nothing.
                                                val online = try {
                                                    withContext(Dispatchers.IO) { EhRustBridge.getOnlineTagSuggestions(trimmed) }
                                                } catch (_: Throwable) { emptyList() }
                                                if (!isActive) return@launch
                                                if (online.isNotEmpty()) {
                                                    // English input: the server knows its own vocabulary best,
                                                    // so rank it above local substring hits. Chinese input can
                                                    // only be answered by the translation DB, so keep that first.
                                                    val isAsciiQuery = trimmed.all { it.code < 128 }
                                                    val first = if (isAsciiQuery) online else local
                                                    val second = if (isAsciiQuery) local else online

                                                    val merged = LinkedHashMap<String, TagSuggestion>()
                                                    for (s in first + second) {
                                                        merged.putIfAbsent(s.raw, s)
                                                    }
                                                    tagSuggestions = merged.values.take(12)
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface, // or Color.White if you want the typed text to be white
                                            fontWeight = FontWeight.Normal
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = {
                                            val q = searchQuery.trim()
                                            if (q.isNotEmpty()) AppSettings.addSearchHistory(context, q)
                                            searchHistory = AppSettings.getSearchHistory(context)
                                            tagSuggestions = emptyList()
                                            isSearchFocused = false
                                            focusManager.clearFocus()
                                            loadGalleries(selectedCategory, searchQuery, isFullReset = true)
                                        }),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { fs ->
                                                isSearchFocused = fs.isFocused
                                            }
                                    )
                                }

                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        tagSuggestions = emptyList()
                                        // isFullReset matters: without it this
                                        // took the "append" branch and prepended
                                        // the front page on top of the previous
                                        // search results, mixing the two lists.
                                        loadGalleries(selectedCategory, null, isFullReset = true)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清空输入",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // History is the one list the user owns outright, so it
                                // gets a way to throw it away. Only shown while history is
                                // the current category — every other listing belongs to the
                                // site and has nothing to clear.
                                if (selectedCategory == DrawerNavCategory.HISTORY) {
                                    IconButton(onClick = {
                                        AppSettings.clearHistory(context)
                                        galleryItems.clear()
                                        Toast.makeText(context, "已清空浏览历史", Toast.LENGTH_SHORT)
                                            .show()
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "清空历史",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                IconButton(onClick = {
                                    val q = searchQuery.trim()
                                    if (q.isNotEmpty()) AppSettings.addSearchHistory(context, q)
                                    searchHistory = AppSettings.getSearchHistory(context)
                                    tagSuggestions = emptyList()
                                    isSearchFocused = false
                                    focusManager.clearFocus()
                                    loadGalleries(selectedCategory, searchQuery, isFullReset = true)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "执行搜索",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // ── Suggestion / History dropdown ──────────────────────
                        if (showDropdown) {
                            Surface(
                                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp)
                                    // The panel covers the results, so a swipe on it never
                                    // reaches the list and no scroll happens to dismiss us.
                                    // Without this the only way to close it was to aim at
                                    // the sliver of list left below it.
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onDragStart = { dismissSuggestions() },
                                            onVerticalDrag = { _, _ -> },
                                        )
                                    }
                            ) {
                                Column {
                                    if (searchQuery.isEmpty()) {
                                        // Empty input: Recent search history header
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = "搜索历史",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (searchHistory.isNotEmpty()) {
                                                Text(
                                                    text = "清除",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.clickable {
                                                        AppSettings.clearSearchHistory(context)
                                                        searchHistory = emptyList()
                                                    }
                                                )
                                            }
                                        }
                                        searchHistory.take(10).forEach { hist ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        searchQuery = hist
                                                        tagSuggestions = emptyList()
                                                        isSearchFocused = false
                                                        focusManager.clearFocus()
                                                        loadGalleries(selectedCategory, hist, isFullReset = true)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.History,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = hist,
                                                    fontSize = 15.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    } else {
                                        // 1. Tag autocomplete suggestions (Tag icon, raw, Chinese translation)
                                        tagSuggestions.take(6).forEach { suggestion ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        val rawTag = suggestion.raw.trim()
                                                        val formatted = if (rawTag.contains(" ") && !rawTag.contains("\"")) {
                                                            val parts = rawTag.split(":", limit = 2)
                                                            if (parts.size == 2) "${parts[0]}:\"${parts[1]}$\"" else "\"$rawTag$\""
                                                        } else {
                                                            "$rawTag$"
                                                        }
                                                        searchQuery = formatted
                                                        AppSettings.addSearchHistory(context, formatted)
                                                        searchHistory = AppSettings.getSearchHistory(context)
                                                        tagSuggestions = emptyList()
                                                        isSearchFocused = false
                                                        focusManager.clearFocus()
                                                        loadGalleries(selectedCategory, formatted, isFullReset = true)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LocalOffer,
                                                    contentDescription = null,
                                                    tint = Color(0xFF00796B),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        text = suggestion.raw,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (suggestion.translated.isNotEmpty() && suggestion.translated != suggestion.raw) {
                                                        Text(
                                                            text = suggestion.translated,
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }

                                        // 2. Matching search history below tag suggestions (Clock icon)
                                        matchingHistory.take(4).forEach { hist ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        searchQuery = hist
                                                        tagSuggestions = emptyList()
                                                        isSearchFocused = false
                                                        focusManager.clearFocus()
                                                        loadGalleries(selectedCategory, hist, isFullReset = true)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.History,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = hist,
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                val pullRefreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        // Raised synchronously: the box waits on this flag before it
                        // starts spinning.
                        isRefreshing = true
                        refreshGalleries()
                    },
                    state = pullRefreshState,
                    // Offset past the floating search bar (54dp row + padding + the
                    // status-bar inset), which otherwise sits exactly on top of the
                    // default indicator position. An indicator that has been offset can
                    // no longer hide itself by sliding out, so it is only composed while
                    // there is something to show.
                    indicator = {
                        if (isRefreshing || pullRefreshState.distanceFraction > 0f) {
                            PullToRefreshDefaults.Indicator(
                                state = pullRefreshState,
                                isRefreshing = isRefreshing,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 70.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    errorMessage != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(onClick = { loadGalleries(selectedCategory, searchQuery, isFullReset = true) }) {
                                Text("重试")
                            }
                        }
                    }

                    galleryItems.isEmpty() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Text(
                                text = "暂无画廊数据",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FilledTonalButton(onClick = { loadGalleries(selectedCategory, searchQuery, isFullReset = true) }) {
                                Text("刷新")
                            }
                        }
                    }

                    else -> {
                        val handleGalleryClick: (com.example.ehviewer_scaffold.rust.GalleryItem) -> Unit = { item ->
                            com.example.ehviewer_scaffold.data.GalleryPreviewHolder.put(item)
                            onGalleryClick(item.gid, item.token, item.thumbUrl)
                        }

                        when (listMode) {

                            // ── Mode 1: 瀑布流 (2-column staggered waterfall) ────────
                            "瀑布流" -> LazyVerticalStaggeredGrid(
                                columns = StaggeredGridCells.Fixed(2),
                                state = gridState,
                                contentPadding = PaddingValues(
                                    start = 14.dp,
                                    end = 14.dp,
                                    top = innerPadding.calculateTopPadding() + 8.dp,
                                    bottom = innerPadding.calculateBottomPadding() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalItemSpacing = 8.dp,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    items = galleryItems,
                                    key = { it.gid },
                                    contentType = { "gallery_item" }
                                ) { item ->
                                    GalleryGridCard(
                                        item = item,
                                        isHapticEnabled = isHapticEnabled,
                                        haptic = haptic,
                                        isListMode = false,
                                        onClick = { handleGalleryClick(item) }
                                    )
                                }
                                if (isLoadingMore || !hasMore) {
                                    item(span = StaggeredGridItemSpan.FullLine, contentType = "footer") {
                                        GalleryListFooter(isLoadingMore)
                                    }
                                }
                            }

                            // ── Mode 2: 列表卡片 (single-column horizontal cards) ────
                            "列表卡片" -> {
                                // Immersive: no side padding — the cover hugs the left
                                // edge and the divider runs the full width. Row spacing
                                // comes from the divider, not from gaps between cards.
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(
                                        top = innerPadding.calculateTopPadding(),
                                        bottom = innerPadding.calculateBottomPadding() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(
                                        items = galleryItems,
                                        key = { it.gid },
                                        contentType = { "gallery_item" }
                                    ) { item ->
                                        GalleryCardItem(
                                            item = item,
                                            isHapticEnabled = isHapticEnabled,
                                            haptic = haptic,
                                            onClick = { handleGalleryClick(item) }
                                        )
                                    }
                                    if (isLoadingMore || !hasMore) {
                                        item(contentType = "footer") {
                                            GalleryListFooter(isLoadingMore)
                                        }
                                    }
                                }
                            }

                            // ── Mode 3: 紧凑网格 (3-column compact grid, cover-only) ─
                            else -> LazyVerticalStaggeredGrid(
                                columns = StaggeredGridCells.Fixed(3),
                                state = gridState,
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = innerPadding.calculateTopPadding() + 8.dp,
                                    bottom = innerPadding.calculateBottomPadding() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalItemSpacing = 6.dp,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    items = galleryItems,
                                    key = { it.gid },
                                    contentType = { "gallery_item" }
                                ) { item ->
                                    GalleryCompactCard(
                                        item = item,
                                        isHapticEnabled = isHapticEnabled,
                                        haptic = haptic,
                                        onClick = { handleGalleryClick(item) }
                                    )
                                }
                                if (isLoadingMore || !hasMore) {
                                    item(span = StaggeredGridItemSpan.FullLine, contentType = "footer") {
                                        GalleryListFooter(isLoadingMore)
                                    }
                                }
                            }
                        }
                    }
                }

                if (isSearchFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) {
                                isSearchFocused = false
                                focusManager.clearFocus()
                            }
                    )
                }
            }
        }
    }
}
}

// ── Shared Footer ─────────────────────────────────────────────────────────────

/**
 * Loading-more spinner / end-of-list indicator, shared by all three list modes.
 */
@Composable
fun GalleryListFooter(isLoadingMore: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    ) {
        if (isLoadingMore) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                text = "— 已经到底啦 —",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
    }
}

// ── Compact Grid Card (3-column, cover-only) ──────────────────────────────────

/**
 * Cover-only card for the 紧凑网格 mode.
 *
 * 3 columns fit the screen width, so each card is ~112 dp wide on a 360 dp screen.
 * The aspect ratio is fixed at 0.72 to match EH thumbnail proportions.
 * A translucent scrim at the bottom carries the category badge without extra height.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryCompactCard(
    item: GalleryItem,
    isHapticEnabled: Boolean,
    haptic: HapticFeedbackManager,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 3 columns at ~112 dp each; decode to the real pixel width (density-scaled)
    // rather than a fixed 360 px box, so a 3x-density screen is not left blurry
    // and a 1x-density screen is not decoding 3x more pixels than it can show.
    val density = LocalDensity.current
    val compactW = with(density) { 130.dp.roundToPx() }
    val compactH = (compactW * 2f).toInt()
    val imageRequest = remember(item.thumbUrl, compactW, compactH) {
        ImageRequest.Builder(context)
            .data(item.thumbUrl)
            .memoryCacheKey(item.thumbUrl)
            .crossfade(false)
            .allowHardware(true)
            .size(compactW, compactH)
            .precision(Precision.INEXACT)
            .build()
    }

    // Same self-correcting ratio as the waterfall card: use the parsed ratio as
    // the placeholder, then adopt the decoded image's shape so a row whose size
    // the parser could not find does not stay letterboxed. See GalleryGridCard.
    var measuredRatio by remember(item.gid) { mutableStateOf(0f) }
    val cellRatio = if (measuredRatio > 0f) measuredRatio else item.coverAspectRatio

    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = Physics.AppleSpringSpec,
        label = "card_scale"
    )

    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (isPressed) 0.9f else 1f
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { if (isHapticEnabled) haptic.heavyClick() }
            )
    ) {
        Box {
            val sharedTransitionScope = LocalSharedTransitionScope.current
            val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                // Fit (not Crop): the cell is sized to the cover's own aspect
                // ratio, so fitting shows the whole cover. A wide 16:9 cover keeps
                // its full width instead of being cropped to a portrait box.
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val decoded = state.result.image
                    if (decoded.width > 0 && decoded.height > 0) {
                        val ratio = decoded.width.toFloat() / decoded.height.toFloat()
                        if (ratio.isFinite() && ratio > 0f &&
                            kotlin.math.abs(ratio - cellRatio) > 0.001f
                        ) {
                            measuredRatio = ratio
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(cellRatio)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    state = rememberSharedContentState(key = "cover-${item.gid}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            }
                        } else Modifier
                    )
            )
            // Category badge overlaid at the bottom of the cover
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            ) {
                CategoryBadge(category = item.category)
            }
        }
    }
}

// ── Gallery Grid Card (Portrait / Waterfall) ──────────────────────────────────

/**
 * Portrait-oriented gallery card for the 2-column waterfall grid.
 *
 * The cell's aspect ratio comes from the cover's *real* dimensions
 * ([GalleryItem.coverAspectRatio], parsed out of the listing HTML). A wide
 * 16:9 cover therefore keeps its full width and renders short instead of being
 * centre-cropped into a portrait box. Only when the server reports no usable
 * dimensions does it fall back to the classic EH portrait ratio (0.72).
 *
 * [ContentScale.Fit] is paired with that dynamic ratio: because the box already
 * matches the cover's ratio there is normally nothing to letterbox, and if a
 * mis-parsed dimension slips through, fitting degrades to a small bar rather
 * than destroying the image edges.
 *
 * The [ImageRequest] is [remember]'d by [thumbUrl], so recompositions triggered
 * by parent state changes do not recreate the Coil request object.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryGridCard(
    item: GalleryItem,
    isHapticEnabled: Boolean,
    haptic: HapticFeedbackManager,
    isListMode: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // Decode budget derived from the *view*, not from the source file.
    //
    // Two failed attempts live in this comment so nobody repeats them:
    //   - A hard-coded 540x750 box rescaled every cover (CPU waste) and, once
    //     cells became aspect-ratio driven, produced a size mismatch against the
    //     layout box so wide covers resolved late.
    //   - Size.ORIGINAL "fixed" that by decoding at native resolution, but EH
    //     covers are up to ~1280x1800 ARGB_8888 = ~9 MB per thumbnail. A screenful
    //     of those blew out the bitmap heap and every eager frame paid for it,
    //     which is exactly the "scroll stutters as images come in" symptom.
    //
    // Decoding to the real on-screen pixel count is both correct and cheap: the
    // card is ~180 dp wide, so ~480 px at 2.6x density, and a tall cover needs
    // well under 1000 px. Coil rounds the request up to its next sampler stop,
    // so this still leaves headroom for a 3-column layout.
    val density = LocalDensity.current
    val targetW: Int
    val targetH: Int
    if (isListMode) {
        // Horizontal card layout renders a ~96 dp square, independent of the cover.
        val px = with(density) { 110.dp.roundToPx() }
        targetW = px
        targetH = px
    } else {
        // Waterfall (2 col) and compact grid (3 col) both live inside a
        // 8..14 dp horizontal inset; size generously for the wider of the two.
        val screenPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
        val colWidth = ((screenPx - with(density) { 28.dp.roundToPx() }) / 2).coerceAtLeast(1)
        targetW = colWidth
        // Cover the tallest realistic case: a 0.4-ratio portrait shim at 2x the
        // width, which is ~5x the card width. Anything taller is clamped by the
        // aspectRatio() cell anyway, so there is no point decoding beyond it.
        targetH = (colWidth * 2f).toInt()
    }
    val imageRequest = remember(item.thumbUrl, targetW, targetH) {
        ImageRequest.Builder(context)
            .data(item.thumbUrl)
            .memoryCacheKey(item.thumbUrl)
            .crossfade(false)
            .allowHardware(true)
            // Decode to the real on-screen size instead of the source size. See
            // the note above for why both the fixed box and ORIGINAL were wrong.
            .size(targetW, targetH)
            .precision(Precision.INEXACT)
            .build()
    }

    // Cell aspect ratio.
    //
    // The listing parse cannot always report a cover's real shape: EH's classic
    // layout puts a *fixed* 250x346 box on `div.glthumb` regardless of the
    // image, and sprite layouts ship no `<img>` with a size at all. Those rows
    // arrive as 0/0 and used to fall back to a hard-coded 0.72 portrait box —
    // and because the image is drawn with `ContentScale.Fit`, any cover that was
    // not portrait got letterboxed into two large grey bars. On a real device
    // that measured as a 581x808 px cell with only ~305 px of image in it.
    //
    // So: still start from the parsed ratio (it is right for the rows where the
    // server did report a size, and it keeps the pre-load placeholder stable),
    // but adopt the decoded image's own ratio as soon as it lands. The cell then
    // always matches what it actually draws, whatever the parser could find.
    var measuredRatio by remember(item.gid) { mutableStateOf(0f) }
    val cellRatio = if (measuredRatio > 0f) measuredRatio else item.coverAspectRatio

    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = Physics.AppleSpringSpec,
        label = "card_scale"
    )

    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (isPressed) 0.9f else 1f
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { if (isHapticEnabled) haptic.heavyClick() }
            )
    ) {
        Column {
            val sharedTransitionScope = LocalSharedTransitionScope.current
            val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

            // Cover image — sized to the cover's own aspect ratio so wide covers
            // are never cropped. The ratio is stable across recompositions, so the
            // layout does not shift once the image resolves.
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    // Snap the cell to the shape we actually decoded. See the
                    // `cellRatio` note above: the parser is the placeholder, the
                    // bitmap is the truth.
                    val decoded = state.result.image
                    if (decoded.width > 0 && decoded.height > 0) {
                        val ratio = decoded.width.toFloat() / decoded.height.toFloat()
                        if (ratio.isFinite() && ratio > 0f &&
                            kotlin.math.abs(ratio - cellRatio) > 0.001f
                        ) {
                            measuredRatio = ratio
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(cellRatio)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    state = rememberSharedContentState(key = "cover-${item.gid}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            }
                        } else Modifier
                    )
            )

            // Text section
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.uploader.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.uploader,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CategoryBadge(category = item.category)
                    if (item.postDate.isNotBlank()) {
                        Text(
                            // Show only the date portion (first 10 chars of "YYYY-MM-DD …")
                            text = item.postDate.take(10),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Legacy Horizontal Card (kept for potential list-mode toggle) ───────────────

/**
 * Horizontal list card retained for compatibility.
 * Not currently shown in the main grid but can be wired to a list/grid toggle.
 */
@Composable
fun GalleryItemCard(
    item: GalleryItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isHapticEnabled = remember { AppSettings.isHapticFeedback(context) }
    val haptic = remember { HapticFeedbackManager(context) }
    GalleryCardItem(item, isHapticEnabled, haptic, onClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryCardItem(
    item: GalleryItem,
    isHapticEnabled: Boolean,
    haptic: HapticFeedbackManager,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // Decode to the rendered width only; height follows the cover's own ratio, which is
    // what the immersive row hangs its layout on.
    val density = LocalDensity.current
    val listThumbPx = with(density) { 112.dp.roundToPx() }
    val imageRequest = remember(item.thumbUrl, listThumbPx) {
        ImageRequest.Builder(context)
            .data(item.thumbUrl)
            .memoryCacheKey(item.thumbUrl)
            .crossfade(false)
            .allowHardware(true)
            .size(listThumbPx)
            .precision(Precision.INEXACT)
            .build()
    }

    // Immersive list row: full-bleed cover hugging the left edge at its own aspect
    // ratio, plain text on the right, a hairline divider between rows. No card chrome
    // anywhere — that is what separates this from a card list.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (isHapticEnabled) haptic.heavyClick() }
            )
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                // Fixed frame rather than the cover's own ratio: every row has to be the
                // same height. Letting a wide cover dictate row height collapsed those
                // rows into a sliver and squeezed the text into it.
                modifier = Modifier
                    .width(112.dp)
                    .height(148.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                // Matches the cover height so the row has one height, and SpaceBetween
                // below has room to push the badge row to the bottom.
                modifier = Modifier
                    .weight(1f)
                    .height(148.dp)
                    .padding(top = 12.dp, bottom = 12.dp, end = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (item.uploader.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.uploader,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CategoryBadge(category = item.category)

                    if (item.postDate.isNotBlank()) {
                        Text(
                            text = item.postDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}

// ── Category Badge ────────────────────────────────────────────────────────────

/**
 * 分类颜色与中文映射胶囊组件（参考图同款）
 */
@Composable
fun CategoryBadge(category: String) {
    // remember(category): Pair allocation and Color lookup run only when the
    // category string actually changes, not on every parent recompose.
    val (label, bgColor) = remember(category) {
        when (category.trim().lowercase()) {
            "doujinshi" -> "DOUJINSHI" to Color(0xFFE53935)
            "manga" -> "MANGA" to Color(0xFFFF9800)
            "artist cg", "artistcg" -> "ARTIST CG" to Color(0xFF8D8D8D)
            "game cg", "gamecg" -> "GAME CG" to Color(0xFF4CAF50)
            "western" -> "WESTERN" to Color(0xFF9C27B0)
            "non-h", "nonh" -> "NON-H" to Color(0xFF2196F3)
            "image set", "imageset" -> "IMAGE SET" to Color(0xFF00BCD4)
            "cosplay" -> "COSPLAY" to Color(0xFF795548)
            "asian porn", "asianporn" -> "ASIAN PORN" to Color(0xFF607D8B)
            "misc" -> "MISC" to Color(0xFFF06292)
            else -> (if (category.isBlank()) "MISC" else category.uppercase()) to Color(0xFFF06292)
        }
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
