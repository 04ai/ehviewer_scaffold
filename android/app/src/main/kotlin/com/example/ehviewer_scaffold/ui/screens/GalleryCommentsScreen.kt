package com.example.ehviewer_scaffold.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.rust.GalleryComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How many extra comment pages the comments screen pulls by itself.
 *
 * Each page is one detail request, and the comments repeat as the image pagination
 * advances, so the useful yield drops off — the cap keeps a gallery with hundreds of
 * comments from stalling here.
 */
private const val COMMENT_MAX_AUTO_PAGES = 5

/**
 * Identity used to dedupe comment pages.
 *
 * Mirrors what `fetch_more_comments` documents on the Rust side: the same
 * comment is re-served as the underlying image pagination advances, and its id
 * is not always parsed, so (author, time, content) is the dependable key.
 */
private fun commentKey(c: GalleryComment): String =
    "${c.author}\u0000${c.time}\u0000${c.content}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryCommentsScreen(
    gid: String,
    token: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Used by "追加" on a comment: it pre-fills the box below and puts the caret in it.
    val focusRequester = remember { FocusRequester() }

    var comments by remember { mutableStateOf<List<GalleryComment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var commentInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreComments by remember { mutableStateOf(true) }
    // Bumped after posting a comment, to pull the list again (a new comment lands on
    // page 1, and the auto-load below then fills in the rest).
    var reloadToken by remember { mutableIntStateOf(0) }

    /**
     * Fetch one page of comments and append whatever is new.
     * Returns false when there is nothing more to add.
     */
    suspend fun fetchCommentPage(page: Int): Boolean {
        if (isLoadingMore) return false
        isLoadingMore = true
        return try {
            val more = withContext(Dispatchers.IO) {
                EhRustBridge.getMoreComments(gid, token, page)
            }
            if (more.isEmpty()) {
                hasMoreComments = false
                false
            } else {
                // Pages overlap — the same comments are re-served as the underlying
                // image pagination advances — so dedupe before appending instead of
                // showing the same comment twice.
                val seen = comments.map { commentKey(it) }.toHashSet()
                val fresh = more.filter { commentKey(it) !in seen }
                if (fresh.isEmpty()) {
                    hasMoreComments = false
                    false
                } else {
                    comments = comments + fresh
                    true
                }
            }
        } catch (_: Exception) {
            hasMoreComments = false
            false
        } finally {
            isLoadingMore = false
        }
    }

    /**
     * Vote a comment up or down.
     *
     * E-Hentai's vote endpoint takes `vote=1` / `vote=-1` on the *same* URL, so a
     * downvote is the upvote link with that one parameter flipped — no second field to
     * parse, and no extra Rust call needed.
     */
    fun voteOn(comment: GalleryComment, up: Boolean) {
        if (comment.voteUrl.isBlank()) {
            Toast.makeText(context, "该评论没有投票链接", Toast.LENGTH_SHORT).show()
            return
        }
        val url = if (up) comment.voteUrl else comment.voteUrl.replace("vote=1", "vote=-1")
        scope.launch {
            val res = try {
                EhRustBridge.voteOnComment(url)
            } catch (e: Exception) {
                "{\"error\":\"${e.message}\"}"
            }
            if (res.contains("\"error\"")) {
                Toast.makeText(context, "投票失败，请检查登录状态", Toast.LENGTH_SHORT).show()
            } else {
                // The endpoint does not echo the new score back, so reflect it locally.
                val delta = if (up) 1 else -1
                comments = comments.map {
                    if (it.voteUrl == comment.voteUrl) it.copy(score = it.score + delta) else it
                }
            }
        }
    }

    // Show page 1 straight away, then keep pulling the rest in the background.
    //
    // This screen used to stop after the first page behind a "load more" button. It is
    // *the* comments screen — it should simply have all of them.
    LaunchedEffect(gid, token, reloadToken) {
        isLoading = true
        try {
            val detail = withContext(Dispatchers.IO) {
                EhRustBridge.getGalleryDetail("$gid/$token")
            }
            comments = detail.comments
            hasMoreComments = true
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
        var page = 1
        while (hasMoreComments && page <= COMMENT_MAX_AUTO_PAGES) {
            if (!fetchCommentPage(page)) break
            page++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (comments.isNotEmpty()) "全部评论 (${comments.size})" else "全部评论",
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
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
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
                                            modifier = Modifier
                                                .focusRequester(focusRequester)
                                                .fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(
                        onClick = {
                            val text = commentInput.trim()
                            if (text.isNotEmpty()) {
                                isSending = true
                                scope.launch(Dispatchers.IO) {
                                    val res = try {
                                        EhRustBridge.sendComment(gid, token, text)
                                    } catch (e: Exception) {
                                        "{\"error\":\"${e.message}\"}"
                                    }
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        if (res.contains("\"error\"")) {
                                            // The result used to be discarded, so a rejected
                                            // comment (expired session, rate limited) still
                                            // toasted "发表成功" and then never appeared.
                                            Toast.makeText(context, "评论发送失败，请检查登录状态", Toast.LENGTH_SHORT).show()
                                        } else {
                                            commentInput = ""
                                            Toast.makeText(context, "评论发表成功", Toast.LENGTH_SHORT).show()
                                            // Repull: the new comment lands on page 1, and
                                            // the auto-load then fills in the rest.
                                            reloadToken++
                                        }
                                    }
                                }
                            }
                        },
                        enabled = commentInput.isNotBlank() && !isSending,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (commentInput.isNotBlank()) Color(0xFF00796B) else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "发送",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                comments.isEmpty() -> {
                    Text(
                        text = "暂无评论",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(comments) { comment ->
                            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // No avatar: E-Hentai has no such thing, and the
                                    // letter-in-a-circle was inventing a feature the
                                    // site does not actually have.
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = comment.author,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = comment.time,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    if (comment.score != 0) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (comment.score > 0) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                                        ) {
                                            Text(
                                                text = if (comment.score > 0) "+${comment.score}" else "${comment.score}",
                                                color = if (comment.score > 0) Color(0xFF2E7D32) else Color(0xFFC62828),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = comment.content,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { voteOn(comment, up = true) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ThumbUp,
                                            contentDescription = "顶",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { voteOn(comment, up = false) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ThumbDown,
                                            contentDescription = "踩",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    // Same reasoning: E-Hentai comments are flat (no
                                    // threading), so appending means pre-filling the box
                                    // with an @name prefix — the way the site itself does
                                    // it. Kept as an icon to match the two vote buttons.
                                    IconButton(
                                        onClick = {
                                            commentInput = "@${comment.author.trim()} "
                                            focusRequester.requestFocus()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Reply,
                                            contentDescription = "追加评论",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // Shown only while the remaining pages are still arriving. There
                        // is no "load more" button any more: this screen pulls them on
                        // its own when it opens.
                        if (isLoadingMore && comments.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "正在加载全部评论…",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
