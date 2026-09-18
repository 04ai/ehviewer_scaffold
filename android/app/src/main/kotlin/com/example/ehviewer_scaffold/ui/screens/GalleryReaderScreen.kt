package com.example.ehviewer_scaffold.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.example.ehviewer_scaffold.rust.EhRustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryReaderScreen(
    gid: String,
    token: String,
    initialPage: Int = 0,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageLoader = remember { SingletonImageLoader.get(context) }

    val imageUrls = remember { mutableStateListOf<String>() }
    val resolvedUris = remember { mutableStateMapOf<String, String>() }
    var totalPages by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    var nextPageToFetch by remember { mutableIntStateOf(1) }
    var isFetchingMore by remember { mutableStateOf(false) }

    // Initial load: fetch page 0 of the gallery detail.
    LaunchedEffect(gid, token) {
        isLoading = true
        imageUrls.clear()
        nextPageToFetch = 1
        try {
            val d = EhRustBridge.getGalleryDetail("$gid/$token")
            totalPages = d.totalPages
            imageUrls.addAll(d.imageUrls)

            // If initialPage is beyond page 0, fetch needed pages
            if (initialPage >= d.imageUrls.size && d.imageUrls.size < d.totalPages) {
                var currentLoaded = d.imageUrls.size
                var p = 1
                while (initialPage >= currentLoaded && currentLoaded < d.totalPages && p <= 10) {
                    val nextD = EhRustBridge.getGalleryPage(gid, token, p)
                    val newUrls = nextD.imageUrls.filter { it !in imageUrls }
                    if (newUrls.isEmpty()) break
                    imageUrls.addAll(newUrls)
                    currentLoaded += newUrls.size
                    p++
                    nextPageToFetch = p
                }
            }
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    if (isLoading || imageUrls.isEmpty()) {
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

    val initialIndex = initialPage.coerceIn(0, (imageUrls.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imageUrls.size }
    )

    // Reset zoom state when switching pages and pre-warm sliding window [cur + 1, cur + 2]
    LaunchedEffect(pagerState.currentPage, imageUrls.size) {
        isCurrentPageZoomed = false

        val cur = pagerState.currentPage
        val preheatRange = (cur + 1)..minOf(cur + 2, imageUrls.size - 1)
        for (nextIdx in preheatRange) {
            val url = imageUrls[nextIdx]
            if (!resolvedUris.containsKey(url)) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val path = EhRustBridge.fetchAndCacheImage(url)
                        if (path.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                resolvedUris[url] = path
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val PREFETCH_THRESHOLD = 5
        val loadedCount = imageUrls.size
        val pagesRemaining = loadedCount - pagerState.currentPage
        val moreAvailable = totalPages == 0 || loadedCount < totalPages

        if (!isFetchingMore && moreAvailable && pagesRemaining <= PREFETCH_THRESHOLD) {
            isFetchingMore = true
            scope.launch {
                try {
                    val d = EhRustBridge.getGalleryPage(gid, token, nextPageToFetch)
                    val newUrls = d.imageUrls.filter { it !in imageUrls }
                    if (newUrls.isNotEmpty()) {
                        imageUrls.addAll(newUrls)
                        nextPageToFetch++
                    }
                } catch (_: Exception) {
                } finally {
                    isFetchingMore = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isCurrentPageZoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            ReaderPageItem(
                viewerUrl = imageUrls[pageIndex],
                localFileUri = resolvedUris[imageUrls[pageIndex]],
                onImageResolved = { url, path ->
                    resolvedUris[url] = path
                },
                pageNumber = pageIndex + 1,
                isCurrentPage = pageIndex == pagerState.currentPage,
                onZoomChanged = { zoomed ->
                    if (pageIndex == pagerState.currentPage) {
                        isCurrentPageZoomed = zoomed
                    }
                },
                onPrevPage = {
                    if (pagerState.currentPage > 0) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }
                },
                onNextPage = {
                    if (pagerState.currentPage < imageUrls.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                onToggleControls = {
                    showControls = !showControls
                }
            )
        }

        // Top Bar Overlay
        if (showControls) {
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
                    .statusBarsPadding()
                    .padding(top = 16.dp)
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
                    val displayTotal = if (totalPages > 0) totalPages else imageUrls.size
                    val loadingIndicator = if (isFetchingMore) "…" else ""
                    Text(
                        "${pagerState.currentPage + 1} / $displayTotal$loadingIndicator",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                    )
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
                val displayTotal = if (totalPages > 0) totalPages else imageUrls.size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        "1",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                    )
                    Slider(
                        value = pagerState.currentPage.toFloat(),
                        onValueChange = { targetPage ->
                            scope.launch {
                                pagerState.scrollToPage(targetPage.toInt())
                            }
                        },
                        valueRange = 0f..(imageUrls.size - 1).toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        "$displayTotal",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                    )
                }
            }
        }
    }
}

/**
 * Single page in the gallery reader.
 *
 * Resolves real CDN image and caches to local disk via Rust pipeline.
 * Then displays the local cached image with full gestures, loading & error-retry support.
 */
@Composable
fun ReaderPageItem(
    viewerUrl: String,
    localFileUri: String?,
    onImageResolved: (String, String) -> Unit,
    pageNumber: Int,
    isCurrentPage: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var containerWidth by remember { mutableFloatStateOf(1000f) }
    var containerHeight by remember { mutableFloatStateOf(2000f) }

    // Reset transform when leaving page
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            onZoomChanged(false)
        }
    }

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

    var isLoading by remember(viewerUrl, localFileUri) { mutableStateOf(localFileUri == null) }
    var loadError by remember(viewerUrl) { mutableStateOf<String?>(null) }

    fun fetchImage() {
        if (localFileUri != null) return
        isLoading = true
        loadError = null
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    EhRustBridge.fetchAndCacheImage(viewerUrl)
                }
                if (path.isNotBlank()) {
                    onImageResolved(viewerUrl, path)
                } else {
                    loadError = "加载图片失败，请重试"
                }
            } catch (e: Exception) {
                loadError = e.localizedMessage ?: "加载异常"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(viewerUrl) {
        if (localFileUri == null) {
            fetchImage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerWidth = size.width.toFloat()
                containerHeight = size.height.toFloat()
            }
            .transformable(
                state = transformState,
                enabled = scale > 1.05f
            )
            .pointerInput(scale) {
                var lastCenterTapTime = 0L
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        val w = size.width
                        val edgeGripMargin = 28.dp.toPx()
                        val x = up.position.x
                        when {
                            // 1. Palm / thumb grip rejection on outer 28dp phone bezels
                            x < edgeGripMargin || x > (w - edgeGripMargin) -> {
                                // Ignored
                            }
                            // 2. Left zone: 0ms INSTANT previous page turn
                            x < w * 0.35f -> {
                                onPrevPage()
                            }
                            // 3. Right zone: 0ms INSTANT next page turn
                            x > w * 0.65f -> {
                                onNextPage()
                            }
                            // 4. Center zone: single tap toggles controls, double tap toggles 2.5x zoom
                            else -> {
                                val now = System.currentTimeMillis()
                                if (now - lastCenterTapTime < 320L) {
                                    lastCenterTapTime = 0L
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                        onZoomChanged(false)
                                    } else {
                                        scale = 2.5f
                                        onZoomChanged(true)
                                    }
                                } else {
                                    lastCenterTapTime = now
                                    onToggleControls()
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "正在加载第 $pageNumber 页...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }
            loadError != null -> {
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
                        text = loadError ?: "加载失败",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(onClick = { fetchImage() }) {
                        Text("点击重试")
                    }
                }
            }
            localFileUri != null -> {
                val imageRequest = remember(localFileUri) {
                    ImageRequest.Builder(context)
                        .data(localFileUri)
                        .allowHardware(true)
                        .bitmapConfig(Bitmap.Config.HARDWARE)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Page $pageNumber",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }
    }
}

