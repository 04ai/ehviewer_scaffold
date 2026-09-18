package com.example.ehviewer_scaffold.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.rust.GalleryThumbnail
import com.example.ehviewer_scaffold.ui.components.EhThumbnail
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryThumbnailsScreen(
    gid: String,
    token: String,
    onThumbnailClick: (page: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val thumbnails = remember { mutableStateListOf<GalleryThumbnail>() }
    var totalPages by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var nextPageToFetch by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }

    // Initial load: fetch page 0 of the gallery
    LaunchedEffect(gid, token) {
        isLoading = true
        thumbnails.clear()
        nextPageToFetch = 1
        try {
            val d = EhRustBridge.getGalleryDetail("$gid/$token")
            totalPages = d.totalPages
            thumbnails.addAll(d.thumbnails)
            if (thumbnails.size >= totalPages || d.thumbnails.isEmpty()) {
                hasMore = false
            }
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    // Infinite scroll trigger
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 9
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (!shouldLoadMore || isLoadingMore || !hasMore || isLoading) return@LaunchedEffect
        isLoadingMore = true
        scope.launch {
            try {
                val d = EhRustBridge.getGalleryPage(gid, token, nextPageToFetch)
                if (d.thumbnails.isEmpty()) {
                    hasMore = false
                } else {
                    thumbnails.addAll(d.thumbnails)
                    nextPageToFetch++
                    if (totalPages > 0 && thumbnails.size >= totalPages) hasMore = false
                }
            } catch (_: Exception) {
            } finally {
                isLoadingMore = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (totalPages > 0) "缩略图 (${thumbnails.size} / $totalPages P)" else "全部缩略图",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                thumbnails.isEmpty() -> {
                    Text(
                        text = "暂无缩略图数据",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    // Exactly 3 columns matching the reference screenshot
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = thumbnails,
                            key = { idx, item -> "${item.url}_${item.offsetX}_${item.offsetY}_$idx" }
                        ) { index, thumb ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onThumbnailClick(index) }
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.72f)
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                ) {
                                    EhThumbnail(
                                        thumb = thumb,
                                        contentDescription = "Page ${index + 1}",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (isLoadingMore || hasMore) {
                            item(span = { GridItemSpan(3) }) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp)
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(24.dp)
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
}
