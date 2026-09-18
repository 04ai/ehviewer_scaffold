package com.example.ehviewer_scaffold.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ehviewer_scaffold.ui.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var downloadDir by remember { mutableStateOf(AppSettings.getDownloadDir(context)) }
    var concurrency by remember { mutableIntStateOf(AppSettings.getDownloadConcurrency(context)) }

    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var showEditDirDialog by remember { mutableStateOf(false) }
    var tempDirText by remember { mutableStateOf(downloadDir) }

    val primaryTeal = Color(0xFF00796B)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ─── 下载目录 ──────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        tempDirText = downloadDir
                        showEditDirDialog = true
                    }
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("下载目录", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = downloadDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                IconButton(onClick = {
                    tempDirText = downloadDir
                    showEditDirDialog = true
                }) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "选择目录",
                        tint = primaryTeal
                    )
                }
            }

            // ─── 同时下载任务数 ───────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showConcurrencyDialog = true }
                    .padding(vertical = 4.dp)
            ) {
                Text("同时下载任务数", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$concurrency",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "选择并发数",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // ─── 并发任务数选择弹窗 ─────────────────────────────────────────────
    if (showConcurrencyDialog) {
        val options = listOf(1, 2, 3, 4, 5)
        AlertDialog(
            onDismissRequest = { showConcurrencyDialog = false },
            title = { Text("选择并发下载任务数", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    options.forEach { n ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (n == concurrency),
                                    onClick = {
                                        concurrency = n
                                        AppSettings.setDownloadConcurrency(context, n)
                                        showConcurrencyDialog = false
                                        Toast.makeText(context, "下载并发数已设为 $n", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = (n == concurrency),
                                onClick = {
                                    concurrency = n
                                    AppSettings.setDownloadConcurrency(context, n)
                                    showConcurrencyDialog = false
                                    Toast.makeText(context, "下载并发数已设为 $n", Toast.LENGTH_SHORT).show()
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryTeal)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("$n 个任务", fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConcurrencyDialog = false }) {
                    Text("取消", color = primaryTeal)
                }
            }
        )
    }

    // ─── 修改下载目录弹窗 ───────────────────────────────────────────────
    if (showEditDirDialog) {
        AlertDialog(
            onDismissRequest = { showEditDirDialog = false },
            title = { Text("修改下载存储目录", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("输入保存已下载画廊的本地文件绝对路径：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempDirText,
                        onValueChange = { tempDirText = it },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempDirText.isNotBlank()) {
                            downloadDir = tempDirText.trim()
                            AppSettings.setDownloadDir(context, downloadDir)
                            Toast.makeText(context, "下载路径已更新", Toast.LENGTH_SHORT).show()
                        }
                        showEditDirDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryTeal)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDirDialog = false }) {
                    Text("取消", color = primaryTeal)
                }
            }
        )
    }
}
