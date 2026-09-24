package com.example.ehviewer_scaffold.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.rust.GalleryComment
import com.example.ehviewer_scaffold.rust.GalleryDetail
import com.example.ehviewer_scaffold.rust.GalleryItem
import com.example.ehviewer_scaffold.rust.GalleryThumbnail
import com.example.ehviewer_scaffold.rust.TagGroup
import com.example.ehviewer_scaffold.rust.TorrentItem
import com.example.ehviewer_scaffold.service.DownloadService
import com.example.ehviewer_scaffold.ui.components.EhThumbnail
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ehviewer_scaffold.utils.LocalAnimatedVisibilityScope
import com.example.ehviewer_scaffold.utils.LocalSharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import com.example.ehviewer_scaffold.ui.theme.Physics

/**
 * How many thumbnail sprites to warm when a gallery detail page opens.
 *
 * The detail page can carry 200+ thumbnails. Firing a Coil request for every
 * one of them simultaneously floods the OkHttp dispatcher, so the cover image
 * that the shared-element transition depends on has to queue behind them.
 * One screenful is enough to make the first scroll feel instant; the rest load
 * on demand as they scroll into view.
 */
private const val SPRITE_PREHEAT_LIMIT = 24

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryDetailScreen(
    gid: String,
    token: String,
    initialCoverUrl: String? = null,
    onReadClick: (gid: String, token: String) -> Unit,
    onReadPageClick: (gid: String, token: String, page: Int) -> Unit = { g, t, _ -> onReadClick(g, t) },
    onViewMoreComments: (gid: String, token: String) -> Unit = { _, _ -> },
    onViewMoreThumbnails: (gid: String, token: String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    onTagSearch: (query: String) -> Unit = {},   // tap-tag → search
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cachedDetail = remember(gid, token) { com.example.ehviewer_scaffold.data.GalleryDetailCache.get("$gid/$token") }
    var detail by remember(gid, token) { mutableStateOf<GalleryDetail?>(cachedDetail) }
    val previewItem = remember(gid) { com.example.ehviewer_scaffold.data.GalleryPreviewHolder.get(gid) }
    val cachedTranslations = remember(gid, token) {
        com.example.ehviewer_scaffold.data.TagTranslationCache.get("$gid/$token")
    }
    var tagTranslations by remember(gid, token) {
        mutableStateOf<Map<String, String>>(cachedTranslations ?: emptyMap())
    }
    var isLoading by remember(gid, token) { mutableStateOf(detail == null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var isFavorited by remember(detail) { mutableStateOf(detail?.isFavorited ?: false) }
    var showMoreInfo by remember { mutableStateOf(false) }
    var showRatingDialog by remember { mutableStateOf(false) }
    var showTorrentsDialog by remember { mutableStateOf(false) }
    var torrentsList by remember { mutableStateOf<List<TorrentItem>>(emptyList()) }
    var isLoadingTorrents by remember { mutableStateOf(false) }
    // Hash of the torrent currently being saved, so only that row shows a spinner.
    var downloadingTorrentHash by remember { mutableStateOf<String?>(null) }
    var showArchiveDialog by remember { mutableStateOf(false) }

    // 阅读进度（0-based 页码；-1 = 没读过）。从阅读器返回时这个屏还在返回栈里，
    // 不会重新创建，所以要靠 lifecycle 在每次 RESUMED 时重新读一次 —— 否则
    // 返回后页码数还停留在进阅读器之前的旧值。
    var savedPage by remember(gid) { mutableIntStateOf(AppSettings.getReadingProgress(context, gid)) }
    // Same reasoning as `savedPage`: this screen stays on the back stack, so the
    // settings that govern how it renders must be re-read on every RESUMED.
    // They used to be plain `remember {}` snapshots, which meant toggling e.g.
    // 「显示标签翻译」 and navigating straight back changed nothing at all.
    var showTagTranslations by remember { mutableStateOf(AppSettings.isShowTagTranslations(context)) }
    var showJapaneseTitle by remember { mutableStateOf(AppSettings.isShowJapaneseTitle(context)) }
    var blockedTags by remember { mutableStateOf(AppSettings.getBlockedTags(context)) }
    var watchedTags by remember { mutableStateOf(AppSettings.getWatchedTags(context)) }
    /** Lowercased block list — the comparison below is case-insensitive. */
    val loweredBlockedTags = remember(blockedTags) { blockedTags.map { it.lowercase() }.toSet() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, gid) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            savedPage = AppSettings.getReadingProgress(context, gid)
            showTagTranslations = AppSettings.isShowTagTranslations(context)
            showJapaneseTitle = AppSettings.isShowJapaneseTitle(context)
            blockedTags = AppSettings.getBlockedTags(context)
            watchedTags = AppSettings.getWatchedTags(context)
        }
    }
    var showResumeDialog by remember { mutableStateOf(false) }

    // Tag long-press action dialog
    var tagActionTarget by remember { mutableStateOf<Pair<String,String>?>(null) } // (namespace, tag)
    var isTagSubscribed by remember { mutableStateOf(false) }
    var isTagBlocked by remember { mutableStateOf(false) }

    var commentInput by remember { mutableStateOf("") }
    var isSendingComment by remember { mutableStateOf(false) }
    var extraComments by remember { mutableStateOf<List<GalleryComment>>(emptyList()) }

    val listState = rememberLazyListState()

    val primaryColor = MaterialTheme.colorScheme.primary
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    // 主题色彩自适应（浅色维持原质感，暗色与自定义主题动态融合）
    val tealDeep = primaryColor
    val chipBg = if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFD9E7EC)
    val chipText = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF00363A)
    val linkTeal = primaryColor
    val fabColor = if (isDark) MaterialTheme.colorScheme.primaryContainer else Color(0xFF80DEEA)
    val fabText = if (isDark) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF00363A)

    /**
     * Resolve Chinese names for every tag of the gallery.
     *
     * Each lookup is a synchronous JNI call into Rust that takes a read lock on
     * the global TAG_DB, so the whole batch goes through
     * [EhRustBridge.translateTags] on Dispatchers.IO (one thread switch for all
     * tags rather than one per tag) and the result is parked in
     * [com.example.ehviewer_scaffold.data.TagTranslationCache] keyed by gid/token.
     */
    suspend fun resolveTagTranslations(detail: GalleryDetail): Map<String, String> {
        val cacheKey = "$gid/$token"
        com.example.ehviewer_scaffold.data.TagTranslationCache.get(cacheKey)?.let { return it }
        val pairs = detail.tagGroups.flatMap { g -> g.tags.map { t -> g.groupName to t } }
        val resolved = EhRustBridge.translateTags(pairs)
        com.example.ehviewer_scaffold.data.TagTranslationCache.put(cacheKey, resolved)
        return resolved
    }

    // Record the visit so the drawer's 历史 has something to show. Kept local because
    // E-Hentai's own /history is gated behind account perks — a cloud-backed list is
    // empty for most people, whereas "what did I open here" is always answerable.
    LaunchedEffect(detail?.id) {
        val d = detail ?: return@LaunchedEffect
        val visitedGid = d.id.substringBefore('/')
        val visitedToken = d.id.substringAfter('/', "")
        if (visitedGid.isBlank() || visitedToken.isBlank()) return@LaunchedEffect
        AppSettings.addHistory(
            context,
            GalleryItem(
                gid = visitedGid,
                token = visitedToken,
                title = d.title,
                thumbUrl = d.coverUrl,
                uploader = d.uploader,
                postDate = d.postDate
            )
        )
    }

    fun loadGalleryDetail(force: Boolean = false) {
        scope.launch {
            if (detail == null || force) {
                isLoading = true
            }
            errorMsg = null
            try {
                // Reuse the cached detail + translations when available. A cached
                // detail means we can paint immediately and skip the whole
                // network + JNI translation round-trip.
                val d = withContext(Dispatchers.IO) {
                    EhRustBridge.getGalleryDetail("$gid/$token")
                }
                tagTranslations = resolveTagTranslations(d)
                detail = d
                isFavorited = d.isFavorited
                com.example.ehviewer_scaffold.data.GalleryDetailCache.put("$gid/$token", d)

                // Warm only the sticky banner + first screenful of sprites.
                // Enqueueing *every* thumbnail (often 200+) at once saturated the
                // OkHttp dispatcher and starved the cover image that the shared
                // element transition is waiting on, which showed up as a stall
                // right after tapping a tag. Sequential + capped keeps the queue
                // short and lets the visible pages win.
                withContext(Dispatchers.IO) {
                    val spriteUrls = d.thumbnails.map { it.url }.filter { it.isNotBlank() }.distinct()
                    val loader = coil3.SingletonImageLoader.get(context)
                    for (sUrl in spriteUrls.take(SPRITE_PREHEAT_LIMIT)) {
                        val clean = com.example.ehviewer_scaffold.ui.components.normalizeThumbUrl(sUrl)
                        val spriteReq = coil3.request.ImageRequest.Builder(context)
                            .data(clean)
                            .crossfade(false)
                            .allowHardware(false)
                            .build()
                        loader.enqueue(spriteReq)
                    }
                }
            } catch (e: Exception) {
                if (detail == null) {
                    errorMsg = e.localizedMessage ?: "加载画廊详情失败"
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(gid, token) {
        if (detail == null) {
            loadGalleryDetail()
        } else if (tagTranslations.isEmpty()) {
            tagTranslations = resolveTagTranslations(detail!!)
        }
    }

    val effectiveDetail = detail ?: previewItem?.let {
        GalleryDetail(
            id = "$gid/$token",
            title = it.title,
            coverUrl = it.thumbUrl,
            uploader = it.uploader,
            postDate = it.postDate,
            tagGroups = if (it.category.isNotBlank()) listOf(TagGroup("category", listOf(it.category))) else emptyList()
        )
    }

    Scaffold(
        floatingActionButton = {
            if (detail != null) {
                // 右下角“开始阅读”悬浮胶囊按钮（完全匹配参考图1）
                Surface(
                    onClick = {
                        // 有进度才问；否则直接从头开始，多一次弹窗只是打扰。
                        if (detail != null && savedPage > 0) {
                            showResumeDialog = true
                        } else {
                            onReadClick(gid, token)
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    color = fabColor,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp, end = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = fabText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "开始阅读",
                            color = fabText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0.dp),
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                isLoading && effectiveDetail == null -> {
                    if (!initialCoverUrl.isNullOrEmpty()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                            ) {
                                val sharedTransitionScope = LocalSharedTransitionScope.current
                                val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

                                AsyncImage(
                                    model = initialCoverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                        .then(
                                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                                with(sharedTransitionScope) {
                                                    Modifier.sharedElement(
                                                        state = rememberSharedContentState(key = "cover-$gid"),
                                                        animatedVisibilityScope = animatedVisibilityScope
                                                    )
                                                }
                                            } else Modifier
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Black.copy(alpha = 0.55f),
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.75f)
                                                )
                                            )
                                        )
                                )
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    IconButton(
                                        onClick = onBack,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "返回",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = linkTeal)
                            }
                        }
                    } else {
                        CircularProgressIndicator(
                            color = linkTeal,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                errorMsg != null && effectiveDetail == null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    ) {
                        Text(errorMsg ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { loadGalleryDetail(force = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = tealDeep)
                        ) {
                            Text("重试")
                        }
                    }
                }
                effectiveDetail != null -> {
                    val d = effectiveDetail
                    val displayTitle = if (showJapaneseTitle && d.titleJpn.isNotBlank()) d.titleJpn else d.title

                    // Preview thumbnails, packed into 3 balanced columns once per detail
                    // payload. Computed *here* rather than inside the LazyColumn's item {}
                    // because remember() is a @Composable call and item {} is a plain
                    // LazyListScope lambda, not a composable scope.
                    val previewThumbs = remember(d.thumbnails) { d.thumbnails.take(15) }
                    val previewColumns = remember(previewThumbs) {
                        val buckets = List(3) { mutableListOf<Pair<Int, GalleryThumbnail>>() }
                        val heights = FloatArray(3)
                        previewThumbs.forEachIndexed { index, thumb ->
                            val ratio = if (thumb.width > 0 && thumb.height > 0) {
                                thumb.width.toFloat() / thumb.height.toFloat()
                            } else {
                                0.72f
                            }
                            // Cell height at a unit column width; the extra term stands
                            // in for the page-number label sitting under each thumbnail.
                            val cellHeight = 1f / ratio + 0.16f
                            val target = heights.indices.minBy { heights[it] }
                            buckets[target].add(index to thumb)
                            heights[target] += cellHeight
                        }
                        buckets
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)
                    ) {
                        // ─── 1. 沉浸式顶部 Banner (参考图1) ───────────────────────────
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                            ) {
                                // 沉浸式宽幅背景封面图（优先复用主页面封面图）
                                val displayCover = d.coverUrl.ifEmpty { initialCoverUrl ?: "" }
                                val sharedTransitionScope = LocalSharedTransitionScope.current
                                val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

                                AsyncImage(
                                    model = displayCover,
                                    contentDescription = d.title,
                                    contentScale = ContentScale.Crop,
                                    placeholder = initialCoverUrl?.let { rememberAsyncImagePainter(it) },
                                    modifier = Modifier.fillMaxSize()
                                        .then(
                                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                                with(sharedTransitionScope) {
                                                    Modifier.sharedElement(
                                                        state = rememberSharedContentState(key = "cover-$gid"),
                                                        animatedVisibilityScope = animatedVisibilityScope
                                                    )
                                                }
                                            } else Modifier
                                        )
                                )

                                // 渐变暗影层（确保白色标题与顶部操作图标在任意明暗封面均清晰可读）
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Black.copy(alpha = 0.55f),
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.75f)
                                                )
                                            )
                                        )
                                )

                                // 覆盖于底部的画廊主标题
                                Text(
                                    text = displayTitle,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        shadow = Shadow(
                                            color = Color.Black.copy(alpha = 0.8f),
                                            offset = Offset(0f, 2f),
                                            blurRadius = 4f
                                        )
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                )
                            }
                        }

                        // ─── 2. 元数据展示区域 (参考图1) ─────────────────────────────
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp, bottom = 8.dp)
                            ) {
                                // 第一行：语言 | 页码数 | 文件大小
                                // 第二行：收藏数 |（空）| 上传时间
                                //
                                // 页码数放在第一行（和语言、大小齐平）——它本来就是同一类信息，
                                // 单独吊在正中间反而像第三类东西。三列等宽 + 顶部对齐即可。
                                val lang = d.language.ifEmpty { "Chinese" }
                                val pages = when {
                                    d.totalPages <= 0 -> ""
                                    savedPage > 0 -> "${(savedPage + 1).coerceAtMost(d.totalPages)}/${d.totalPages}P"
                                    else -> "${d.totalPages}P"
                                }
                                val size = d.fileSize
                                val date = d.postDate.ifEmpty { previewItem?.postDate ?: "" }

                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                ) {
                                    // 左上：语言　左下：收藏数
                                    Column(
                                        horizontalAlignment = Alignment.Start,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = lang,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Favorite,
                                                contentDescription = "收藏数",
                                                tint = Color(0xFFE53935),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = d.favoritesCount.ifEmpty { "0" },
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    // 中上：页码数（只有一行，顶部对齐即与左右齐平）
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = pages,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // 右上：文件大小　右下：上传时间
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = size,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = date,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // “查看更多信息”链接按钮
                                Text(
                                    text = if (showMoreInfo) "收起更多信息" else "查看更多信息",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = linkTeal,
                                    modifier = Modifier
                                        .clickable { showMoreInfo = !showMoreInfo }
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )

                                // 可展开的详细信息卡片
                                if (showMoreInfo) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 8.dp)
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(14.dp)
                                        ) {
                                            Text("上传者: ${d.uploader}", fontSize = 13.sp)
                                            Text("评分: ${d.rating}", fontSize = 13.sp)
                                            if (d.titleJpn.isNotBlank()) {
                                                Text("日文标题: ${d.titleJpn}", fontSize = 13.sp)
                                            }
                                            Text("画廊识别码: $gid / $token", fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }

                        if (detail == null) {
                            item {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 24.dp)
                                ) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = linkTeal,
                                        trackColor = linkTeal.copy(alpha = 0.2f)
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "正在同步画廊标签与缩略图...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            // ─── 3. 操作按钮栏（6个图标按钮均匀排布，参考图1）─────────
                            item {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                ) {
                                    // 1. 收藏
                                    DetailActionButton(
                                        icon = if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        iconTint = if (isFavorited) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        label = "收藏",
                                        onClick = {
                                            scope.launch {
                                                val targetFav = !isFavorited
                                                // Optimistic, then verified. The result used
                                                // to be discarded outright, so a request that
                                                // failed (expired session, 5xx) still toasted
                                                // "已添加到收藏夹" and left the heart filled.
                                                isFavorited = targetFav
                                                val res = try {
                                                    EhRustBridge.submitFavorite(
                                                        gid,
                                                        token,
                                                        if (targetFav) "0" else "-1",
                                                        ""
                                                    )
                                                } catch (e: Exception) {
                                                    "{\"error\":\"${e.message}\"}"
                                                }
                                                if (res.contains("\"error\"")) {
                                                    isFavorited = !targetFav // roll back
                                                    Toast.makeText(
                                                        context,
                                                        "收藏失败，请检查登录状态",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        if (targetFav) "已添加到收藏夹" else "已从收藏夹移除",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    )

                                    // 2. 评分
                                    DetailActionButton(
                                        icon = Icons.Outlined.StarRate,
                                        label = "评分",
                                        onClick = { showRatingDialog = true }
                                    )

                                    // 3. 下载
                                    DetailActionButton(
                                        icon = Icons.Outlined.FileDownload,
                                        label = "下载",
                                        onClick = {
                                            scope.launch {
                                                val ok = EhRustBridge.triggerDownload(
                                                    gid = gid,
                                                    token = token,
                                                    title = d.title,
                                                    imageUrls = d.imageUrls,
                                                    totalPages = d.totalPages
                                                )
                                                if (ok) {
                                                    DownloadService.start(context)
                                                }
                                                Toast.makeText(
                                                    context,
                                                    if (ok) "已加入下载队列" else "加入下载失败",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    )

                                    // 4. 种子 (数量)
                                    val torrentsCount = d.torrentCount.ifEmpty { "0" }
                                    DetailActionButton(
                                        icon = Icons.Default.Bolt,
                                        label = "种子 ($torrentsCount)",
                                        onClick = {
                                            showTorrentsDialog = true
                                            if (torrentsList.isEmpty()) {
                                                isLoadingTorrents = true
                                                scope.launch(Dispatchers.IO) {
                                                    val list = EhRustBridge.getTorrents(gid, token)
                                                    withContext(Dispatchers.Main) {
                                                        torrentsList = list
                                                        isLoadingTorrents = false
                                                    }
                                                }
                                            }
                                        }
                                    )

                                    // 5. 档案
                                    DetailActionButton(
                                        icon = Icons.Outlined.Inventory2,
                                        label = "档案",
                                        onClick = { showArchiveDialog = true }
                                    )

                                    // 6. 分享
                                    DetailActionButton(
                                        icon = Icons.Outlined.Share,
                                        label = "分享",
                                        onClick = {
                                            // Follow the site setting — this was fixed
                                            // to e-hentai.org, so an ExHentai user shared
                                            // a link to a gallery that is not listed there.
                                            val host = if (AppSettings.isExHentai(context)) {
                                                "https://exhentai.org"
                                            } else {
                                                "https://e-hentai.org"
                                            }
                                            val shareUrl = "$host/g/$gid/$token/"
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, "${d.title}\n$shareUrl")
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "分享画廊"))
                                        }
                                    )
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }

                            // ─── 3.5 屏蔽提示 ──────────────────────────────────────
                            //
                            // Blocking a tag hides its galleries from listings, but
                            // this screen can still be reached (a stale back-stack
                            // entry, a link, a direct tap before the block took
                            // effect). Say so instead of leaving the user confused
                            // about why it no longer shows up elsewhere.
                            item {
                                val hasBlockedTag = remember(d, loweredBlockedTags) {
                                    d.tagGroups.any { group ->
                                        group.tags.any { tag ->
                                            "${group.groupName}:$tag".lowercase() in loweredBlockedTags
                                        }
                                    }
                                }
                                if (hasBlockedTag) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Block,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "此画廊包含已屏蔽的标签，列表中不会再出现",
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // ─── 4. 标签分类区域 (参考图1) ───────────────────────────────
                            item {
                                // Aliases for the RESUMED-refreshed state above. These
                                // used to be snapshotted per-detail, so a tag blocked in
                                // settings did not show as blocked until the screen was
                                // recreated.
                                val blockedTagsSet = blockedTags
                                val watchedTagsSet = watchedTags

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    d.tagGroups.forEach { group ->
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            // 命名空间胶囊深色徽章 (深青色底，白色文字，精巧紧凑)
                                            Surface(
                                                color = tealDeep,
                                                shape = CircleShape,
                                                modifier = Modifier.padding(top = 2.dp, end = 6.dp)
                                            ) {
                                                Text(
                                                    text = group.groupName,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                                                )
                                            }

                                            // 标签芯片列表 (圆角浅青色底色，间距紧凑)
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                verticalArrangement = Arrangement.spacedBy(5.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                group.tags.forEach { tag ->
                                                    val fullTag = "${group.groupName}:$tag"
                                                    val translated = if (showTagTranslations) {
                                                        tagTranslations[fullTag] ?: tag
                                                    } else tag
                                                    val isBlocked = fullTag in blockedTagsSet

                                                    Surface(
                                                        color = if (isBlocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else chipBg,
                                                        shape = CircleShape,
                                                        modifier = Modifier.combinedClickable(
                                                            onClick = {
                                                                // Single tap: tag search
                                                                onTagSearch(fullTag)
                                                            },
                                                            onLongClick = {
                                                                // Long press: show subscribe/block dialog
                                                                isTagBlocked = fullTag in blockedTagsSet
                                                                isTagSubscribed = fullTag in watchedTagsSet
                                                                tagActionTarget = Pair(group.groupName, tag)
                                                            }
                                                        )
                                                    ) {
                                                        Text(
                                                            text = translated,
                                                            fontSize = 11.sp,
                                                            color = if (isBlocked) MaterialTheme.colorScheme.error else chipText,
                                                            fontWeight = FontWeight.Medium,
                                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }

                        // ─── 5. 评论区域 (完全对齐参考截图) ─────────────────────────
                        item {
                            val allComments = (detail?.comments ?: emptyList()) + extraComments
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                if (allComments.isEmpty()) {
                                    // 暂无评论（居中浅灰色）
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 18.dp)
                                    ) {
                                        Text(
                                            text = "暂无评论",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    // 精选展示最多2条最新评论
                                    allComments.take(2).forEach { comment ->
                                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = comment.author,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = comment.time,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = comment.content,
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }

                                    // “查看更多评论”居中文字链接 → 跳转独立评论页面
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "查看更多评论",
                                            color = linkTeal,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp,
                                            modifier = Modifier
                                                .clickable { onViewMoreComments(gid, token) }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }

                                // “添加评论...”输入框
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp, bottom = 14.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        TextField(
                                            value = commentInput,
                                            onValueChange = { commentInput = it },
                                            placeholder = {
                                                Text(
                                                    "添加评论...",
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent,
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent
                                            ),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            val text = commentInput.trim()
                                            if (text.isNotEmpty()) {
                                                isSendingComment = true
                                                scope.launch(Dispatchers.IO) {
                                                    val res = try {
                                                        EhRustBridge.sendComment(gid, token, text)
                                                    } catch (e: Exception) {
                                                        "{\"error\":\"${e.message}\"}"
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        isSendingComment = false
                                                        if (res.contains("\"error\"")) {
                                                            // Result used to be discarded: a
                                                            // rejected comment still toasted
                                                            // "发表成功" and never showed up.
                                                            Toast.makeText(
                                                                context,
                                                                "评论发送失败，请检查登录状态",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        } else {
                                                            commentInput = ""
                                                            Toast.makeText(context, "评论发表成功", Toast.LENGTH_SHORT).show()
                                                            // Append locally rather than calling
                                                            // loadGalleryDetail(): a full reload
                                                            // refetched the page and re-preheated
                                                            // 24 thumbnails to display one comment.
                                                            extraComments = extraComments + GalleryComment(
                                                                author = "我",
                                                                time = "刚刚",
                                                                content = text
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        enabled = commentInput.isNotBlank() && !isSendingComment,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(
                                                if (commentInput.isNotBlank()) tealDeep else Color.Gray.copy(alpha = 0.3f),
                                                CircleShape
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Send,
                                            contentDescription = "发送评论",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // ─── 6. 画廊预览缩略图预览（3 列，带序号，底部药丸按钮“查看更多缩略图”）──
                        //
                        // IMPORTANT: this must NOT be a LazyVerticalStaggeredGrid.
                        // It is rendered inside the parent LazyColumn's item {}, which
                        // measures its children with an *infinite* max-height constraint.
                        // A lazy staggered grid is a vertically scrollable container and
                        // throws IllegalStateException when measured against infinity
                        // ("Vertically scrollable component was measured with an infinity
                        // maximum height constraints"). userScrollEnabled = false only
                        // disables the scroll *gesture* - the container still measures
                        // itself lazily, so it still crashes.
                        //
                        // Instead we lay the thumbnails out ourselves: bucket them into
                        // 3 columns by running height, so every cell keeps its own aspect
                        // ratio (wide pages stay wide) without any scrolling container.
                        // previewThumbs / previewColumns are computed above, outside the
                        // LazyListScope, so no remember() runs in a non-composable lambda.
                        if (previewThumbs.isNotEmpty()) {
                            item {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp)
                                ) {
                                    previewColumns.forEach { column ->
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            column.forEach { (pageIndex, thumb) ->
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onReadPageClick(gid, token, pageIndex) }
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .aspectRatio(
                                                                if (thumb.width > 0 && thumb.height > 0) {
                                                                    thumb.width.toFloat() / thumb.height.toFloat()
                                                                } else {
                                                                    0.72f
                                                                }
                                                            )
                                                            .border(
                                                                width = 1.dp,
                                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                                                shape = RoundedCornerShape(4.dp)
                                                            )
                                                    ) {
                                                        EhThumbnail(
                                                            thumb = thumb,
                                                            contentDescription = "Page ${pageIndex + 1}",
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "${pageIndex + 1}",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 居中椭圆描边按钮“查看更多缩略图” → 跳转独立缩略图页面
                            item {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp, bottom = 16.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onViewMoreThumbnails(gid, token) },
                                        shape = RoundedCornerShape(24.dp),
                                        border = BorderStroke(1.dp, linkTeal.copy(alpha = 0.7f)),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = linkTeal
                                        ),
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    ) {
                                        Text(
                                            text = "查看更多缩略图",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                        }

                        // 底部留白给悬浮阅读按钮
                        item {
                            Spacer(modifier = Modifier.navigationBarsPadding().height(90.dp))
                        }
                    }

                    // ─── 苹果风格顶部跟随栏 (Apple-style Sticky Top Bar) ──────────
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val bannerHeightPx = with(density) { 280.dp.toPx() }
                    val thresholdPx = bannerHeightPx - with(density) { 90.dp.toPx() }
                    val isImprinted by remember {
                        derivedStateOf {
                            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > thresholdPx
                        }
                    }

                    Surface(
                        color = if (isImprinted) MaterialTheme.colorScheme.surface.copy(alpha = 0.95f) else Color.Transparent,
                        shadowElevation = if (isImprinted) 4.dp else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            // 返回按钮（纯箭头圆形胶囊，紧凑对齐状态栏）
                            Surface(
                                color = if (isImprinted) Color.Transparent else Color.Black.copy(alpha = 0.35f),
                                shape = CircleShape,
                                onClick = onBack,
                                modifier = Modifier.clip(CircleShape)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回",
                                        tint = if (isImprinted) MaterialTheme.colorScheme.onSurface else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // 标题
                            if (isImprinted) {
                                Text(
                                    text = displayTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            // 刷新按钮
                            IconButton(
                                onClick = { loadGalleryDetail() },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(if (isImprinted) Color.Transparent else Color.Black.copy(alpha = 0.35f), CircleShape)
                                    .border(
                                        width = if (isImprinted) 0.dp else 0.5.dp,
                                        color = if (isImprinted) Color.Transparent else Color.White.copy(alpha = 0.2f),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    tint = if (isImprinted) MaterialTheme.colorScheme.onSurface else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── 继续阅读 / 重新阅读 ──────────────────────────────────────────────────
    // 只在"有进度"时出现。从第 1 页继续没有任何意义，所以 savedPage == 0
    // 直接进阅读器，不弹窗。
    if (showResumeDialog && detail != null) {
        val resumePage = savedPage.coerceIn(0, (detail!!.totalPages - 1).coerceAtLeast(0))
        AlertDialog(
            onDismissRequest = { showResumeDialog = false },
            title = { Text("继续阅读？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "上次读到第 ${resumePage + 1} 页，共 ${detail!!.totalPages} 页。",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    onReadPageClick(gid, token, resumePage)
                }) { Text("继续阅读") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    onReadClick(gid, token)
                }) { Text("重新阅读") }
            }
        )
    }

    // ─── 评分弹窗 ─────────────────────────────────────────────────────────────
    if (showRatingDialog) {
        var selectedStars by remember { mutableStateOf(5) }
        AlertDialog(
            onDismissRequest = { showRatingDialog = false },
            title = { Text("为画廊评分", fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                ) {
                    (1..5).forEach { star ->
                        IconButton(onClick = { selectedStars = star }) {
                            Icon(
                                imageVector = if (star <= selectedStars) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "$star 星",
                                tint = if (star <= selectedStars) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedStars <= 0) {
                        // The dialog allowed submitting 0 stars, which E-Hentai
                        // rejects — now it is refused locally instead.
                        Toast.makeText(context, "请先选择星数", Toast.LENGTH_SHORT).show()
                    } else {
                        scope.launch {
                            // Result checked: this used to always report success.
                            val res = try {
                                EhRustBridge.rate(gid, token, selectedStars)
                            } catch (e: Exception) {
                                "{\"error\":\"${e.message}\"}"
                            }
                            if (res.contains("\"error\"")) {
                                Toast.makeText(context, "评分失败，请检查登录状态", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "评分已提交: $selectedStars 星", Toast.LENGTH_SHORT).show()
                                showRatingDialog = false
                            }
                        }
                    }
                }) { Text("提交评分") }
            },
            dismissButton = {
                TextButton(onClick = { showRatingDialog = false }) { Text("取消") }
            }
        )
    }

    // ─── 种子弹窗 ─────────────────────────────────────────────────────────────
    if (showTorrentsDialog) {
        AlertDialog(
            onDismissRequest = { showTorrentsDialog = false },
            title = { Text("画廊种子列表", fontWeight = FontWeight.Bold) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    when {
                        isLoadingTorrents -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        torrentsList.isEmpty() -> Text("暂无可用的种子文件", modifier = Modifier.align(Alignment.Center))
                        else -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(torrentsList) { torrent ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(torrent.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("大小: ${torrent.sizeText}  做种: ${torrent.seeds}  下载: ${torrent.downloads}", fontSize = 12.sp)
                                            }
                                            // 保存 .torrent 到 <下载目录>/torrents/
                                            if (downloadingTorrentHash == torrent.hash) {
                                                CircularProgressIndicator(
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            } else {
                                                IconButton(
                                                    onClick = {
                                                        downloadingTorrentHash = torrent.hash
                                                        scope.launch {
                                                            val saved = EhRustBridge.fetchTorrentFile(
                                                                name = torrent.name,
                                                                hash = torrent.hash,
                                                                token = torrent.token
                                                            )
                                                            downloadingTorrentHash = null
                                                            Toast.makeText(
                                                                context,
                                                                if (saved.isEmpty()) "种子下载失败，请检查登录状态与网络"
                                                                else "种子已保存到: $saved",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Download,
                                                        contentDescription = "下载种子文件",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTorrentsDialog = false }) { Text("关闭") }
            }
        )
    }

    // ─── 档案弹窗 ─────────────────────────────────────────────────────────────
    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text("画廊档案 (H@H Archiver)", fontWeight = FontWeight.Bold) },
            text = {
                Text("支持通过 E-Hentai Archiver 服务将全本画廊打包下载至本地。\n\n需要登录账号且持有相应的 Hath 积分。")
            },
            confirmButton = {
                TextButton(onClick = { showArchiveDialog = false }) { Text("了解") }
            }
        )
    }

    // ─── 标签长按操作弹窗（订阅 / 屏蔽）──────────────────────────────────────
    val currentTagTarget = tagActionTarget
    if (currentTagTarget != null) {
        val namespace = currentTagTarget.first
        val tag = currentTagTarget.second
        val fullTag = "$namespace:$tag"
        val translated = if (showTagTranslations) {
            tagTranslations[fullTag] ?: tag
        } else tag

        AlertDialog(
            onDismissRequest = { tagActionTarget = null },
            title = {
                Column {
                    Text(
                        text = fullTag,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (translated != tag) {
                        Text(
                            text = translated,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ── 订阅 / 取消订阅 ──────────────────────────────────────
                    if (isTagSubscribed) {
                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    EhRustBridge.removeWatchedTag(fullTag)
                                }
                                AppSettings.removeWatchedTag(context, fullTag)
                                isTagSubscribed = false
                                tagActionTarget = null
                                Toast.makeText(context, "已取消订阅: $translated", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subscriptions,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("取消订阅此标签")
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    EhRustBridge.addWatchedTag(fullTag)
                                }
                                AppSettings.addWatchedTag(context, fullTag)
                                isTagSubscribed = true
                                tagActionTarget = null
                                Toast.makeText(context, "已订阅标签: $translated", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tealDeep,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subscriptions,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("订阅此标签")
                        }
                    }

                    // ── 屏蔽 / 取消屏蔽 ─────────────────────────────────────
                    if (isTagBlocked) {
                        OutlinedButton(
                            onClick = {
                                AppSettings.removeBlockedTag(context, fullTag)
                                isTagBlocked = false
                                tagActionTarget = null
                                Toast.makeText(context, "已取消屏蔽: $translated", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("取消屏蔽")
                        }
                    } else {
                        Button(
                            onClick = {
                                AppSettings.addBlockedTag(context, fullTag)
                                isTagBlocked = true
                                tagActionTarget = null
                                Toast.makeText(
                                    context,
                                    "已屏蔽标签: $translated（含此标签的画廊将不再出现在列表中）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("屏蔽此标签")
                        }
                    }

                    // ── 以此标签搜索（快捷方式）──────────────────────────────
                    TextButton(
                        onClick = {
                            tagActionTarget = null
                            onTagSearch(fullTag)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("搜索此标签")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { tagActionTarget = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 详情页 6 大操作按钮组件（图标 + 文字，参考图1）
 */
@Composable
private fun DetailActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
