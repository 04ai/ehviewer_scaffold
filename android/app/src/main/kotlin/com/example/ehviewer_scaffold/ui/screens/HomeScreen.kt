package com.example.ehviewer_scaffold.ui.screens

import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Precision
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.rust.GalleryItem
import com.example.ehviewer_scaffold.rust.SearchOptions
import com.example.ehviewer_scaffold.rust.TagSuggestion
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.ehviewer_scaffold.utils.HapticFeedbackManager
import com.example.ehviewer_scaffold.utils.SquircleShape
import com.example.ehviewer_scaffold.utils.appleCardClickable
import com.example.ehviewer_scaffold.utils.LocalAnimatedVisibilityScope
import com.example.ehviewer_scaffold.utils.LocalSharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import com.example.ehviewer_scaffold.ui.theme.Physics

/**
 * 抽屉菜单项枚举：与 EH 网站云端特性对应
 */
enum class DrawerNavCategory(
    val title: String,
    val icon: ImageVector,
    val cloudPath: String? // null 表示本地导航（下载、设置）
) {
    HOME("主页", Icons.Default.Home, ""),
    WATCHED("订阅", Icons.Default.Subscriptions, "watched"),
    POPULAR("热门", Icons.Default.Whatshot, "popular"),
    TOPLIST("排行榜", Icons.Default.Leaderboard, "toplist.php"),
    FAVORITES("收藏", Icons.Default.Favorite, "favorites.php"),
    HISTORY("历史", Icons.Default.History, "history"),
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

    val gridState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()

    // Read the list-mode preference from AppSettings.
    var listMode by remember { mutableStateOf(AppSettings.getListMode(context)) }

    // Build SearchOptions from user's search settings
    fun buildSearchOptions() = SearchOptions(
        fSname = AppSettings.isSearchIncludeName(context),
        fStags = AppSettings.isSearchIncludeTags(context),
        fSdesc = AppSettings.isSearchIncludeDesc(context),
        fCats = null // category filter is applied separately via the URL path
    )
    LaunchedEffect(Unit) { listMode = AppSettings.getListMode(context) }

    // ── Infinite Scroll Trigger (Seamless Prefetching before hitting bottom) ───
    // Use snapshotFlow to continuously observe scroll position. The old
    // LaunchedEffect(shouldLoadMore) approach only fired when the derived boolean
    // *changed* value, so after the first load-more the effect would never
    // re-trigger if the user was still near the bottom.
    LaunchedEffect(listMode, selectedCategory, hasMore) {
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
                if (!nearEnd || isLoadingMore || !hasMore || isLoading) return@collect
                isLoadingMore = true
                try {
                    val nextPage = currentPage + 1
                    val path = selectedCategory.cloudPath ?: ""
                    val opts = buildSearchOptions()
                    val result = if (path.isEmpty()) {
                        EhRustBridge.getGalleryList(page = nextPage, pageUrl = currentNextUrl, query = currentQuery, options = opts)
                    } else {
                        EhRustBridge.getCustomList(path = path, page = nextPage, pageUrl = currentNextUrl, query = currentQuery, options = opts)
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

    // ── Initial / Search Load ─────────────────────────────────────────────────
    fun loadGalleries(category: DrawerNavCategory, query: String? = null, isFullReset: Boolean = false) {
        scope.launch {
            isLoading = true
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
                val path = category.cloudPath ?: ""
                val opts = buildSearchOptions()
                val result = if (path.isEmpty()) {
                    EhRustBridge.getGalleryList(page = 0, query = currentQuery, options = opts)
                } else {
                    EhRustBridge.getCustomList(path = path, page = 0, query = currentQuery, options = opts)
                }
                currentNextUrl = result.nextUrl
                if (initialQuery.isNullOrBlank()) {
                    HomeStateHolder.currentNextUrl = result.nextUrl
                }
                android.util.Log.d("HomeScreen", "loadGalleries result: count=${result.items.size}, nextUrl=${result.nextUrl}")
                if (result.items.isNotEmpty()) {
                    if (isFullReset) {
                        galleryItems.addAll(result.items)
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
                if (result.items.size < 20) {
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

    // ── Pull-to-refresh at the top (接续刷新，不丢失当前浏览进度与已缓存项) ──
    fun refreshGalleries() {
        scope.launch {
            isRefreshing = true
            try {
                val path = selectedCategory.cloudPath ?: ""
                val opts = buildSearchOptions()
                val result = if (path.isEmpty()) {
                    EhRustBridge.getGalleryList(page = 0, query = currentQuery, options = opts)
                } else {
                    EhRustBridge.getCustomList(path = path, page = 0, query = currentQuery, options = opts)
                }
                if (result.items.isNotEmpty()) {
                    val existingGids = galleryItems.map { it.gid }.toSet()
                    val newItems = result.items.filter { it.gid !in existingGids }
                    if (newItems.isNotEmpty()) {
                        galleryItems.addAll(0, newItems)
                    }
                }
            } catch (_: Exception) {
            } finally {
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

    // ── Drawer + Scaffold ─────────────────────────────────────────────────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                modifier = Modifier.width(300.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { drawerState.close() }
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
                            scope.launch { drawerState.close() }
                            when (item) {
                                DrawerNavCategory.DOWNLOADS -> onNavigateToDownloads()
                                DrawerNavCategory.SETTINGS -> onNavigateToSettings()
                                else -> {
                                    selectedCategory = item
                                    searchQuery = ""
                                    loadGalleries(item, null, isFullReset = true)
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        },
        modifier = modifier
    ) {
        Scaffold(
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
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
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
                                    if (searchQuery.isEmpty()) {
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
                                                if (q.isNotBlank()) {
                                                    val suggestions = EhRustBridge.searchTags(q)
                                                    tagSuggestions = suggestions
                                                } else {
                                                    tagSuggestions = emptyList()
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = {
                                            val q = searchQuery.trim()
                                            if (q.isNotEmpty()) AppSettings.addSearchHistory(context, q)
                                            searchHistory = AppSettings.getSearchHistory(context)
                                            tagSuggestions = emptyList()
                                            focusManager.clearFocus()
                                            loadGalleries(selectedCategory, searchQuery)
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
                                        loadGalleries(selectedCategory, null)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清空输入",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                IconButton(onClick = {
                                    val q = searchQuery.trim()
                                    if (q.isNotEmpty()) AppSettings.addSearchHistory(context, q)
                                    searchHistory = AppSettings.getSearchHistory(context)
                                    tagSuggestions = emptyList()
                                    focusManager.clearFocus()
                                    loadGalleries(selectedCategory, searchQuery)
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
                                                        focusManager.clearFocus()
                                                        loadGalleries(selectedCategory, hist)
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
                                                        focusManager.clearFocus()
                                                        loadGalleries(selectedCategory, formatted)
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
                                                        focusManager.clearFocus()
                                                        loadGalleries(selectedCategory, hist)
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

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refreshGalleries() },
                modifier = Modifier
                    .fillMaxSize()
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
                        when (listMode) {

                            // ── Mode 1: 瀑布流 (2-column staggered waterfall) ────────
                            "瀑布流" -> LazyVerticalStaggeredGrid(
                                columns = StaggeredGridCells.Fixed(2),
                                state = gridState,
                                contentPadding = PaddingValues(
                                    start = 14.dp,
                                    end = 14.dp,
                                    top = innerPadding.calculateTopPadding() + 8.dp,
                                    bottom = innerPadding.calculateBottomPadding() + 16.dp
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
                                        onClick = { onGalleryClick(item.gid, item.token, item.thumbUrl) }
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
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(
                                        start = 14.dp,
                                        end = 14.dp,
                                        top = innerPadding.calculateTopPadding() + 8.dp,
                                        bottom = innerPadding.calculateBottomPadding() + 16.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(
                                        items = galleryItems,
                                        key = { it.gid },
                                        contentType = { "gallery_item" }
                                    ) { item ->
                                        GalleryCardItem(
                                            item = item,
                                            onClick = { onGalleryClick(item.gid, item.token, item.thumbUrl) }
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
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                                        onClick = { onGalleryClick(item.gid, item.token, item.thumbUrl) }
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
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryCompactCard(
    item: GalleryItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(item.thumbUrl) {
        ImageRequest.Builder(context)
            .data(item.thumbUrl)
            .memoryCacheKey(item.thumbUrl)
            .crossfade(false)
            .allowHardware(true)
            .size(Dimension(360), Dimension(500))
            .precision(Precision.INEXACT)
            .build()
    }

    val haptic = remember { HapticFeedbackManager(context) }

    Card(
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .appleCardClickable(haptic = haptic, onClick = onClick)
    ) {
        Box {
            val sharedTransitionScope = LocalSharedTransitionScope.current
            val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
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
 * Image aspect ratio is fixed at 0.72 (≈ EH thumbnail proportions 250×346 px)
 * so all images occupy the same proportional height within their column.
 * [ContentScale.Crop] fills the frame without letterboxing.
 *
 * The [ImageRequest] is [remember]'d by [thumbUrl], so recompositions triggered
 * by parent state changes do not recreate the Coil request object.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryGridCard(
    item: GalleryItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(item.thumbUrl) {
        ImageRequest.Builder(context)
            .data(item.thumbUrl)
            .memoryCacheKey(item.thumbUrl)
            .crossfade(false)
            .allowHardware(true)
            .size(Dimension(540), Dimension(750))
            .precision(Precision.INEXACT)
            .build()
    }

    val haptic = remember { HapticFeedbackManager(context) }

    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .appleCardClickable(haptic = haptic, onClick = onClick)
    ) {
        Column {
            val sharedTransitionScope = LocalSharedTransitionScope.current
            val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

            // Cover image — fixed aspect ratio ensures stable layout before load.
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
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
) = GalleryCardItem(item, onClick)

@Composable
fun GalleryCardItem(
    item: GalleryItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(item.thumbUrl) {
        ImageRequest.Builder(context)
            .data(item.thumbUrl)
            .memoryCacheKey(item.thumbUrl)
            .crossfade(false)
            .allowHardware(true)
            .size(Dimension(300), Dimension(420))
            .precision(Precision.INEXACT)
            .build()
    }

    val haptic = remember { HapticFeedbackManager(context) }

    Card(
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .appleCardClickable(haptic = haptic, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.dp)
                    .height(136.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 136.dp),
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
                        Spacer(modifier = Modifier.height(4.dp))
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
            "doujinshi" -> "Doujinshi" to Color(0xFFE53935)
            "manga" -> "Manga" to Color(0xFFFF9800)
            "artist cg", "artistcg" -> "Artist CG" to Color(0xFFE91E63)
            "game cg", "gamecg" -> "Game CG" to Color(0xFF4CAF50)
            "western" -> "Western" to Color(0xFF9C27B0)
            "non-h", "nonh" -> "Non-H" to Color(0xFF2196F3)
            "image set", "imageset" -> "Image Set" to Color(0xFF00BCD4)
            "cosplay" -> "Cosplay" to Color(0xFF795548)
            "asian porn", "asianporn" -> "Asian Porn" to Color(0xFF607D8B)
            "misc" -> "Misc" to Color(0xFFF06292)
            else -> (if (category.isBlank()) "Misc" else category) to Color(0xFFF06292)
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
