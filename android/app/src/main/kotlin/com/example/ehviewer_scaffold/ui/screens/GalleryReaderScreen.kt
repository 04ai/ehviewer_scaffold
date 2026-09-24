package com.example.ehviewer_scaffold.ui.screens

import android.app.Activity
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.StrokeCap
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Upper bound for the per-page download-progress poll interval (see below). */
private const val PROGRESS_POLL_MAX_INTERVAL_MS = 400L

/**
 * Pause between successive background thumbnail-batch fetches.
 *
 * Deliberately non-zero: this worker walks every remaining page batch, and
 * firing them back-to-back floods the shared connection pool and starves the
 * pages the reader is actually showing. Named (rather than an inline literal)
 * so the trade-off is visible and tunable.
 */
private const val THUMBNAIL_BATCH_PAUSE_MS = 600L

/**
 * Coil model for one page source.
 *
 * Online pages are viewer URLs that `RustImageFetcher` routes into the Rust
 * pipeline. Offline pages are absolute filesystem paths, and Coil only resolves
 * those reliably when it is handed a `File` — a bare path string is not a
 * `file://` URI and can fall through to the network fetchers.
 */
private fun pageImageModel(source: String): Any =
    if (source.startsWith("http")) source else java.io.File(source)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryReaderScreen(
    gid: String,
    token: String,
    initialPage: Int = 0,
    /**
     * Read the pages already saved on disk instead of fetching them.
     *
     * Until this existed, a finished download was a dead end: the bytes were
     * on the device but nothing in the app could open them. In offline mode the
     * page source map holds absolute file paths rather than viewer URLs, and
     * every network path (batch fetching, progress polling) is skipped.
     */
    offline: Boolean = false,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageLoader = remember { SingletonImageLoader.get(context) }

    // ── 读取阅读器设置 ──────────────────────────────────────────────────────
    val readerDirection = remember { AppSettings.getReaderDirection(context) }
    val isFullscreen    = remember { AppSettings.isReaderFullscreen(context) }
    val showClock       = remember { AppSettings.isReaderShowClock(context) }
    val showBattery     = remember { AppSettings.isReaderShowBattery(context) }
    val autoPageSeconds = remember { AppSettings.getReaderAutoPage(context) }
    val pageIntervalDp  = remember { AppSettings.getReaderPageInterval(context).coerceIn(0, 50) }
    val customBrightness = remember { AppSettings.getReaderCustomBrightness(context) }

    // 自定义亮度：-1 跟随系统，否则 0..100 映射到 0..1
    DisposableEffect(customBrightness) {
        val activity = context as? Activity
        if (activity != null && customBrightness >= 0) {
            val lp = activity.window.attributes
            lp.screenBrightness = (customBrightness / 100f).coerceIn(0.01f, 1f)
            activity.window.attributes = lp
        }
        onDispose {
            val activity2 = context as? Activity
            activity2?.let {
                val lp = it.window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.window.attributes = lp
            }
        }
    }

    // 全屏：隐藏系统状态栏 + 导航栏
    val windowInsetsController = remember {
        val activity = context as? Activity
        activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView) }
    }
    DisposableEffect(isFullscreen) {
        if (isFullscreen) {
            windowInsetsController?.hide(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE.let {
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            })
            windowInsetsController?.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            windowInsetsController?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    // 动态时钟（每 30 秒刷新）
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(showClock) {
        if (!showClock) return@LaunchedEffect
        while (true) {
            clockText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(30_000L)
        }
    }

    // 动态电量监听广播（阅读过程中电量变化实时同步）
    var batteryPct by remember { mutableIntStateOf(-1) }
    DisposableEffect(showBattery) {
        if (!showBattery) {
            onDispose { }
        } else {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    if (level >= 0 && scale > 0) {
                        val newPct = level * 100 / scale
                        if (newPct != batteryPct) {
                            batteryPct = newPct
                        }
                    }
                }
            }
            val filter = IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val initialStatus = context.registerReceiver(receiver, filter)
            val initialLevel = initialStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val initialScale = initialStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (initialLevel >= 0 && initialScale > 0) {
                val newPct = initialLevel * 100 / initialScale
                if (newPct != batteryPct) {
                    batteryPct = newPct
                }
            }
            onDispose {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
        }
    }

    val pageViewerUrls = remember { mutableStateMapOf<Int, String>() }
    var totalPages by remember { mutableIntStateOf(0) }
    var pageSize by remember { mutableIntStateOf(20) }
    var isLoading by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    val loadedThumbnailPages = remember { mutableSetOf<Int>() }
    val inFlightThumbnailPages = remember { mutableSetOf<Int>() }

    fun ensureThumbnailPage(p: Int) {
        if (p < 0) return
        // Offline pages are never fetched in batches: the whole list is loaded
        // up front from disk.
        if (offline) return
        val maxPage = if (totalPages > 0) (totalPages - 1) / pageSize else Int.MAX_VALUE
        if (p > maxPage) return
        if (p in loadedThumbnailPages || p in inFlightThumbnailPages) return

        inFlightThumbnailPages.add(p)
        scope.launch(Dispatchers.IO) {
            try {
                val d = EhRustBridge.getGalleryPage(gid, token, p)
                val base = p * pageSize
                withContext(Dispatchers.Main) {
                    d.imageUrls.forEachIndexed { i, url ->
                        pageViewerUrls[base + i] = url
                    }
                    loadedThumbnailPages.add(p)
                }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    inFlightThumbnailPages.remove(p)
                }
            }
        }
    }

    // Initial load: fetch page 0 of the gallery detail (or the on-disk page
    // list when reading a finished download).
    LaunchedEffect(gid, token, offline) {
        isLoading = true
        pageViewerUrls.clear()
        loadedThumbnailPages.clear()
        try {
            if (offline) {
                val paths = withContext(Dispatchers.IO) {
                    EhRustBridge.getDownloadedPagePaths(gid)
                }
                paths.forEachIndexed { i, path -> pageViewerUrls[i] = path }
                totalPages = paths.size
                pageSize = paths.size.coerceAtLeast(1)
                loadedThumbnailPages.add(0)
            } else {
                val d = withContext(Dispatchers.IO) {
                    EhRustBridge.getGalleryDetail("$gid/$token")
                }
                totalPages = d.totalPages
                val detectedSize = if (d.imageUrls.isNotEmpty()) d.imageUrls.size else 20
                pageSize = detectedSize
                d.imageUrls.forEachIndexed { i, url ->
                    pageViewerUrls[i] = url
                }
                loadedThumbnailPages.add(0)

                // If initialPage is beyond page 0, fetch the thumbnail batch containing it
                val initialP = initialPage / detectedSize
                if (initialP > 0) {
                    ensureThumbnailPage(initialP)
                }
            }
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    // Gentle background worker: fetch all remaining thumbnail pages
    LaunchedEffect(totalPages, pageSize, offline) {
        if (offline) return@LaunchedEffect
        if (totalPages <= 0) return@LaunchedEffect
        val totalBatchPages = (totalPages - 1) / pageSize
        for (p in 1..totalBatchPages) {
            if (p !in loadedThumbnailPages) {
                ensureThumbnailPage(p)
                delay(THUMBNAIL_BATCH_PAUSE_MS)
            }
        }
    }

    val realTotalPages = if (totalPages > 0) totalPages else pageViewerUrls.size.coerceAtLeast(1)

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    // A distinct state from "still loading": nothing is ever going to appear.
    // This used to be folded into the branch above, so a failed detail fetch
    // (or an empty download directory in offline mode) left the reader
    // spinning forever with no way to tell what went wrong.
    if (pageViewerUrls.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (offline) "本地没有已下载的图片" else "未能加载图片列表",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = onBack) {
                    Text("返回", color = Color.White)
                }
            }
        }
        return
    }

    val initialIndex = initialPage.coerceIn(0, (realTotalPages - 1).coerceAtLeast(0))

    // 判断是否垂直方向（上下连续模式）
    val isVertical = readerDirection.contains("上下连续")
    // 判断是否从右向左（翻转水平方向）
    val isRtl = readerDirection == "从右向左"

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { realTotalPages }
    )

    var sliderPosition by remember { mutableStateOf<Float?>(null) }
    val currentDisplayPage = if (sliderPosition != null) {
        (sliderPosition!!.roundToInt() + 1).coerceIn(1, realTotalPages)
    } else {
        (pagerState.currentPage + 1).coerceIn(1, realTotalPages)
    }

    // 阅读进度：翻页即落盘，这样"退出"不需要任何钩子 —— 中途被杀进程、
    // 直接划掉任务、按返回键，结果都一样。写入用 apply()（异步提交），
    // 不在翻页路径上做磁盘 I/O。
    LaunchedEffect(pagerState.currentPage, realTotalPages) {
        if (realTotalPages > 0) {
            AppSettings.saveReadingProgress(context, gid, pagerState.currentPage)
        }
    }

    // 自动翻页定时器
    LaunchedEffect(autoPageSeconds, pagerState.currentPage) {
        if (autoPageSeconds <= 0 || isVertical) return@LaunchedEffect
        delay(autoPageSeconds * 1000L)
        val next = pagerState.currentPage + 1
        if (next < realTotalPages) {
            pagerState.animateScrollToPage(next)
        }
    }

    // 当前页变更时：预取前后批次，并使用 Coil 预热后 3 页图片
    LaunchedEffect(pagerState.currentPage, totalPages, pageSize) {
        isCurrentPageZoomed = false

        val cur = pagerState.currentPage
        val currentP = cur / pageSize
        ensureThumbnailPage(currentP)
        ensureThumbnailPage(currentP + 1)
        if (currentP > 0) ensureThumbnailPage(currentP - 1)

        val preheatRange = (cur + 1)..minOf(cur + 3, realTotalPages - 1)
        for (nextIdx in preheatRange) {
            val url = pageViewerUrls[nextIdx] ?: continue
            val preheatRequest = ImageRequest.Builder(context)
                .data(pageImageModel(url))
                .memoryCacheKey(url)
                .allowHardware(true)
                .build()
            imageLoader.enqueue(preheatRequest)
        }
    }

    // 页面内容 lambda（横/纵向 Pager 共用）
    val pageContent: @Composable (pageIndex: Int) -> Unit = { pageIndex ->
        val url = pageViewerUrls[pageIndex]
        if (url != null) {
            ReaderPageItem(
                viewerUrl = url,
                pageNumber = pageIndex + 1,
                isCurrentPage = pageIndex == pagerState.currentPage,
                onZoomChanged = { zoomed ->
                    if (pageIndex == pagerState.currentPage) isCurrentPageZoomed = zoomed
                },
                onPrevPage = {
                    val target = pagerState.currentPage - 1
                    if (target >= 0) scope.launch { pagerState.animateScrollToPage(target) }
                },
                onNextPage = {
                    val target = pagerState.currentPage + 1
                    if (target < realTotalPages) scope.launch { pagerState.animateScrollToPage(target) }
                },
                onToggleControls = { showControls = !showControls },
                isRtl = isRtl
            )
        } else {
            // 尚未就绪：触发加载并显示精简加载圈
            LaunchedEffect(pageIndex) {
                ensureThumbnailPage(pageIndex / pageSize)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${pageIndex + 1}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                        strokeWidth = 3.dp,
                        strokeCap = StrokeCap.Round
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isVertical) {
            VerticalPager(
                state = pagerState,
                userScrollEnabled = !isCurrentPageZoomed,
                beyondViewportPageCount = 2,
                pageSpacing = pageIntervalDp.dp,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                pageContent(pageIndex)
            }
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isCurrentPageZoomed,
                beyondViewportPageCount = 2,
                pageSpacing = pageIntervalDp.dp,
                reverseLayout = isRtl,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                pageContent(pageIndex)
            }
        }

        // Top Bar Overlay
        if (showControls) {
            val topCutout = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
            val topStatusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val safeTopPadding = maxOf(topCutout, topStatusBar, 38.dp) + 12.dp

            // Top HUD Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.65f),
                border = BorderStroke(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.20f), Color.Black.copy(alpha = 0.08f))
                    )
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = safeTopPadding)
                    .wrapContentSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "$currentDisplayPage / $realTotalPages",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                    )
                    // 时钟 & 电量
                    if (showClock && clockText.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(clockText, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                    if (showBattery && batteryPct >= 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔋$batteryPct%", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                }
            }

            // Bottom Slider Pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.65f),
                border = BorderStroke(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.20f), Color.Black.copy(alpha = 0.08f))
                    )
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        "$currentDisplayPage",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.widthIn(min = 28.dp)
                    )
                    Slider(
                        value = (sliderPosition ?: pagerState.currentPage.toFloat()).coerceIn(0f, (realTotalPages - 1).coerceAtLeast(1).toFloat()),
                        onValueChange = { targetPage ->
                            sliderPosition = targetPage
                        },
                        onValueChangeFinished = {
                            sliderPosition?.let { pos ->
                                val target = pos.roundToInt().coerceIn(0, (realTotalPages - 1).coerceAtLeast(0))
                                scope.launch {
                                    pagerState.scrollToPage(target)
                                }
                                sliderPosition = null
                            }
                        },
                        valueRange = 0f..(realTotalPages - 1).coerceAtLeast(1).toFloat(),
                        enabled = realTotalPages > 1,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF26A69A),
                            activeTrackColor = Color(0xFF26A69A),
                            inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        "$realTotalPages",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.widthIn(min = 28.dp)
                    )
                }
            }
        }
    }
}

/**
 * 极致流畅的阅读器单页组件：
 * 1. AsyncImage 永久驻留在布局树中，消除组件增删导致的重新布局/测量与掉帧。
 * 2. 缩放平移使用 draw-phase 专用的 graphicsLayer { ... }，不触发 Recompose/Layout。
 * 3. 采用 detectTapGestures 替代 raw awaitEachGesture，完全不与 Pager 的垂直/水平滑动发生争抢。
 * 4. 彻底交由 Coil 的 RustImageFetcher 异步后台流式解码与硬件位图渲染。
 */
@Composable
fun ReaderPageItem(
    viewerUrl: String,
    pageNumber: Int,
    isCurrentPage: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    isRtl: Boolean = false
) {
    val context = LocalContext.current

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var containerWidth by remember { mutableFloatStateOf(1000f) }
    var containerHeight by remember { mutableFloatStateOf(2000f) }

    // 离开当前页时重置缩放
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            onZoomChanged(false)
        }
    }

    // Whether a second finger is down right now.
    //
    // `transformable` consumes single-finger pans as well, so enabling it
    // unconditionally would swallow the pager's swipe. The previous version
    // gated it on `scale > 1.05f`, which disabled the gesture exactly when the
    // user first tried to pinch — zoom was only reachable by double-tap.
    // Observing the pointer count on the Initial pass is read-only (nothing is
    // consumed there) and keeps single-finger swiping with the pager while
    // letting a two-finger pinch through.
    var multiTouch by remember { mutableStateOf(false) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = newScale
        val isZoomed = newScale > 1.05f
        onZoomChanged(isZoomed)

        if (isZoomed) {
            val maxOffsetX = (newScale - 1f) * (containerWidth / 2f)
            val maxOffsetY = (newScale - 1f) * (containerHeight / 2f)
            offsetX = (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
            offsetY = (offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    var isImageLoading by remember(viewerUrl) { mutableStateOf(true) }
    var isImageError by remember(viewerUrl) { mutableStateOf(false) }
    var errorMessage by remember(viewerUrl) { mutableStateOf<String?>(null) }
    var retryTrigger by remember(viewerUrl) { mutableIntStateOf(0) }
    var downloadProgress by remember(viewerUrl, retryTrigger) { mutableFloatStateOf(0f) }

    LaunchedEffect(viewerUrl, isImageLoading, retryTrigger) {
        if (!isImageLoading) return@LaunchedEffect
        // Nothing to report for a local file: the bytes are already on the
        // device, so polling would just burn JNI transitions for a progress arc
        // that has no meaningful intermediate states.
        if (!viewerUrl.startsWith("http")) return@LaunchedEffect
        // Poll with backoff rather than a flat 50 ms.
        //
        // Every iteration costs a thread hop plus a JNI transition, and in the
        // vertical reader several pages can be loading simultaneously — so a flat
        // 50 ms meant tens of native calls per second purely to animate a
        // progress arc. A progress indicator does not need 20 fps: stay
        // responsive for the first moments (when progress actually moves) and
        // back off once the download settles into its long tail.
        var intervalMs = 50L
        while (isActive && isImageLoading) {
            val p = withContext(Dispatchers.IO) {
                EhRustBridge.getImageProgress(viewerUrl)
            }
            if (p > downloadProgress) {
                downloadProgress = p
            }
            if (p >= 1f) break
            delay(intervalMs)
            intervalMs = (intervalMs * 3 / 2).coerceAtMost(PROGRESS_POLL_MAX_INTERVAL_MS)
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pageDownloadProgress"
    )

    val imageRequest = remember(viewerUrl, retryTrigger) {
        ImageRequest.Builder(context)
            .data(pageImageModel(viewerUrl))
            .memoryCacheKey(viewerUrl)
            .allowHardware(true)
            .crossfade(100)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerWidth = size.width.toFloat()
                containerHeight = size.height.toFloat()
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val isMulti = event.changes.count { it.pressed } >= 2
                        if (isMulti != multiTouch) multiTouch = isMulti
                    }
                }
            }
            .transformable(
                state = transformState,
                // See `multiTouch`: a pinch has to work at scale 1, and the
                // single finger has to stay with the pager.
                enabled = multiTouch || scale > 1.05f
            )
            .pointerInput(isRtl) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            onZoomChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomChanged(true)
                        }
                    },
                    onTap = { offset ->
                        val w = size.width
                        val edgeGripMargin = 28.dp.toPx()
                        val x = offset.x
                        when {
                            x < edgeGripMargin || x > (w - edgeGripMargin) -> {
                                // 忽略边缘握持误触
                            }
                            x < w * 0.35f -> {
                                if (isRtl) onNextPage() else onPrevPage()
                            }
                            x > w * 0.65f -> {
                                if (isRtl) onPrevPage() else onNextPage()
                            }
                            else -> {
                                onToggleControls()
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // AsyncImage 永久驻留，零重组和重新测量损耗
        AsyncImage(
            model = imageRequest,
            contentDescription = "Page $pageNumber",
            contentScale = ContentScale.Fit,
            onLoading = {
                isImageLoading = true
                isImageError = false
            },
            onSuccess = {
                isImageLoading = false
                isImageError = false
            },
            onError = { result ->
                isImageLoading = false
                isImageError = true
                errorMessage = result.result.throwable.localizedMessage ?: "加载图片失败"
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        )

        // 精简加载占位层：上方为页数，下方为当前页真实下载进度动态圈
        if (isImageLoading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$pageNumber",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(14.dp))
                CircularProgressIndicator(
                    progress = { animatedProgress.coerceIn(0.04f, 1f) },
                    modifier = Modifier.size(36.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeWidth = 3.dp,
                    strokeCap = StrokeCap.Round
                )
            }
        }

        // 错误重试层
        if (isImageError) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage ?: "加载失败",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(onClick = { retryTrigger++ }) {
                    Text("点击重试")
                }
            }
        }
    }
}
