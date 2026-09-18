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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    var comments by remember { mutableStateOf<List<GalleryComment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var commentInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    fun loadComments() {
        scope.launch {
            isLoading = true
            try {
                val detail = EhRustBridge.getGalleryDetail("$gid/$token")
                comments = detail.comments
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(gid, token) {
        loadComments()
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
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(
                        onClick = {
                            val text = commentInput.trim()
                            if (text.isNotEmpty()) {
                                isSending = true
                                scope.launch(Dispatchers.IO) {
                                    val res = EhRustBridge.sendComment(gid, token, text)
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        commentInput = ""
                                        Toast.makeText(context, "评论发表成功", Toast.LENGTH_SHORT).show()
                                        loadComments()
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
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00796B).copy(alpha = 0.2f))
                                    ) {
                                        Text(
                                            text = comment.author.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00796B),
                                            fontSize = 15.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
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
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
