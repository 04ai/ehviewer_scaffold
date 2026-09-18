package com.example.ehviewer_scaffold.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ehviewer_scaffold.rust.DownloadTask
import com.example.ehviewer_scaffold.rust.EhRustBridge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun refreshTasks() {
        scope.launch {
            isLoading = true
            try {
                tasks = EhRustBridge.getDownloads()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshTasks()
    }

    LaunchedEffect(tasks) {
        val hasActive = tasks.any { it.status == 1 }
        if (hasActive) {
            com.example.ehviewer_scaffold.service.DownloadService.start(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTasks() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
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
                isLoading && tasks.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                tasks.isEmpty() -> {
                    Text("暂无下载任务", modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(tasks, key = { it.gid }) { task ->
                            DownloadTaskCard(
                                task = task,
                                onPause = {
                                    scope.launch {
                                        EhRustBridge.pauseDownload(task.gid)
                                        refreshTasks()
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        EhRustBridge.deleteDownload(task.gid)
                                        refreshTasks()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                val (statusText, statusColor) = when (task.status) {
                    1 -> "下载中" to MaterialTheme.colorScheme.primary
                    2 -> "已完成" to Color(0xFF4CAF50)
                    -1 -> "失败" to MaterialTheme.colorScheme.error
                    else -> "已暂停" to MaterialTheme.colorScheme.onSurfaceVariant
                }

                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val progress = if (task.totalPages > 0) {
                task.downloadedPages.toFloat() / task.totalPages.toFloat()
            } else 0f

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${task.downloadedPages} / ${task.totalPages} 页 (${(progress * 100).toInt()}%)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row {
                    if (task.status == 1) {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                        }
                    } else if (task.status != 2) {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
