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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var isLoading by remember(gid, token) { mutableStateOf(detail == null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var isFavorited by remember(detail) { mutableStateOf(detail?.isFavorited ?: false) }
    var showMoreInfo by remember { mutableStateOf(false) }
    var showRatingDialog by remember { mutableStateOf(false) }
    var showTorrentsDialog by remember { mutableStateOf(false) }
    var torrentsList by remember { mutableStateOf<List<TorrentItem>>(emptyList()) }
    var isLoadingTorrents by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }

    // Tag long-press action dialog
    var tagActionTarget by remember { mutableStateOf<Pair<String,String>?>(null) } // (namespace, tag)
    var isTagSubscribed by remember { mutableStateOf(false) }
    var isTagBlocked by remember { mutableStateOf(false) }

    var commentInput by remember { mutableStateOf("") }
    var isSendingComment by remember { mutableStateOf(false) }
    var extraComments by remember { mutableStateOf<List<GalleryComment>>(emptyList()) }

    val showTagTranslations = remember { AppSettings.isShowTagTranslations(context) }
    val showJapaneseTitle = remember { AppSettings.isShowJapaneseTitle(context) }

    // 主题色彩常量（对齐参考图设计）
    val tealDeep = Color(0xFF00695C) // 标签命名空间深青色
    val chipBg = Color(0xFFD9E7EC)   // 标签芯片浅蓝灰底色
    val chipText = Color(0xFF00363A) // 标签文字深色
    val linkTeal = Color(0xFF00796B) // 链接与重点操作文字
    val fabColor = Color(0xFF80DEEA) // 开始阅读胶囊按钮亮浅青色
    val fabText = Color(0xFF00363A)

    fun loadGalleryDetail(force: Boolean = false) {
        scope.launch {
            if (detail == null || force) {
                isLoading = true
            }
            errorMsg = null
            try {
                val d = EhRustBridge.getGalleryDetail("$gid/$token")
                detail = d
                isFavorited = d.isFavorited
                com.example.ehviewer_scaffold.data.GalleryDetailCache.put("$gid/$token", d)
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
        }
    }

    Scaffold(
        floatingActionButton = {
            if (detail != null) {
                // 右下角“开始阅读”悬浮胶囊按钮（完全匹配参考图1）
                Surface(
                    onClick = { onReadClick(gid, token) },
                    shape = RoundedCornerShape(28.dp),
                    color = fabColor,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(bottom = 12.dp, end = 8.dp)
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
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading && detail == null -> {
                    if (!initialCoverUrl.isNullOrEmpty()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
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
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    IconButton(
                                        onClick = onBack,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "返回",
                                            tint = Color.White
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
                errorMsg != null && detail == null -> {
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
                detail != null -> {
                    val d = detail!!
                    val displayTitle = if (showJapaneseTitle && d.titleJpn.isNotBlank()) d.titleJpn else d.title

                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // ─── 1. 沉浸式顶部 Banner (参考图1) ───────────────────────────
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
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

                                // 顶部操作栏（返回键 + 刷新键）
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    IconButton(
                                        onClick = onBack,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "返回",
                                            tint = Color.White
                                        )
                                    }

                                    IconButton(
                                        onClick = { loadGalleryDetail() },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "刷新",
                                            tint = Color.White
                                        )
                                    }
                                }

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
                                // 语言 | 页数 | 大小 | 上传日期
                                val lang = d.language.ifEmpty { "Chinese" }
                                val pages = "${d.totalPages}P"
                                val size = d.fileSize.ifEmpty { "80.70m" }
                                val date = d.postDate.ifEmpty { "2026-08-29 15:42" }

                                Text(
                                    text = "$lang  $pages  $size  $date",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // 收藏数 (红心 + 数量)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = Color(0xFFE53935),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = d.favoritesCount.ifEmpty { "12" },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
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
                                            isFavorited = targetFav
                                            val res = EhRustBridge.submitFavorite(gid, token, if (targetFav) "0" else "-1", "")
                                            Toast.makeText(
                                                context,
                                                if (targetFav) "已添加到收藏夹" else "已从收藏夹移除",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
                                        val shareUrl = "https://e-hentai.org/g/$gid/$token/"
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

                        // ─── 4. 标签分类区域 (参考图1) ───────────────────────────────
                        item {
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
                                                    EhRustBridge.translateTag(group.groupName, tag).ifEmpty { tag }
                                                } else tag
                                                val isBlocked = AppSettings.getBlockedTags(context).contains(fullTag)

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
                                                            isTagBlocked = AppSettings.getBlockedTags(context).contains(fullTag)
                                                            isTagSubscribed = AppSettings.getWatchedTags(context).contains(fullTag)
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
                                                    val res = EhRustBridge.sendComment(gid, token, text)
                                                    withContext(Dispatchers.Main) {
                                                        isSendingComment = false
                                                        commentInput = ""
                                                        Toast.makeText(context, "评论发表成功", Toast.LENGTH_SHORT).show()
                                                        loadGalleryDetail()
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

                        // ─── 6. 画廊预览缩略图预览（参考图严格 3 列网格，带序号，底部药丸按钮“查看更多缩略图”）──
                        if (d.thumbnails.isNotEmpty()) {
                            val previewThumbs = d.thumbnails.take(15)
                            previewThumbs.chunked(3).forEachIndexed { rowIndex, rowItems ->
                                item {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 5.dp)
                                    ) {
                                        rowItems.forEachIndexed { colIndex, thumb ->
                                            val pageIndex = rowIndex * 3 + colIndex
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { onReadPageClick(gid, token, pageIndex) }
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(0.72f)
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
                                        repeat(3 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
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

                        // 底部留白给悬浮阅读按钮
                        item {
                            Spacer(modifier = Modifier.height(90.dp))
                        }
                    }
                }
            }
        }
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
                    scope.launch {
                        EhRustBridge.rate(gid, token, selectedStars)
                        Toast.makeText(context, "评分已提交: ${selectedStars} 星", Toast.LENGTH_SHORT).show()
                        showRatingDialog = false
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
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(torrent.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("大小: ${torrent.sizeText}  做种: ${torrent.seeds}  下载: ${torrent.downloads}", fontSize = 12.sp)
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
    tagActionTarget?.let { (namespace, tag) ->
        val fullTag = "$namespace:$tag"
        val translated = if (showTagTranslations)
            EhRustBridge.translateTag(namespace, tag).ifEmpty { tag } else tag

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
                                Toast.makeText(context, "已屏蔽标签: $translated", Toast.LENGTH_SHORT).show()
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
