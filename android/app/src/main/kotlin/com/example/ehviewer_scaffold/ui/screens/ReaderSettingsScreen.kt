package com.example.ehviewer_scaffold.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var readerDirection by remember { mutableStateOf(AppSettings.getReaderDirection(context)) }
    var isFullscreen by remember { mutableStateOf(AppSettings.isReaderFullscreen(context)) }
    var showClock by remember { mutableStateOf(AppSettings.isReaderShowClock(context)) }
    var showBattery by remember { mutableStateOf(AppSettings.isReaderShowBattery(context)) }
    var autoPageSeconds by remember { mutableFloatStateOf(AppSettings.getReaderAutoPage(context).toFloat()) }
    var pageIntervalPx by remember { mutableFloatStateOf(AppSettings.getReaderPageInterval(context).toFloat()) }
    var customBrightness by remember { mutableFloatStateOf(AppSettings.getReaderCustomBrightness(context).toFloat()) }

    var showDirectionDialog by remember { mutableStateOf(false) }

    val primaryTeal = Color(0xFF00796B)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // ─── 阅读方向 ──────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDirectionDialog = true }
                    .padding(vertical = 4.dp)
            ) {
                Text("阅读方向", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Text(
                    text = readerDirection,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ─── 全屏阅读 ──────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("全屏阅读", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Switch(
                    checked = isFullscreen,
                    onCheckedChange = {
                        isFullscreen = it
                        AppSettings.setReaderFullscreen(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 显示系统时钟 ──────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("显示系统时钟", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Switch(
                    checked = showClock,
                    onCheckedChange = {
                        showClock = it
                        AppSettings.setReaderShowClock(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 显示电池电量 ──────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("显示电池电量", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Switch(
                    checked = showBattery,
                    onCheckedChange = {
                        showBattery = it
                        AppSettings.setReaderShowBattery(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 自动翻页 (秒) ──────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("自动翻页 (秒)", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Text(
                        text = if (autoPageSeconds.roundToInt() <= 0) "关闭" else "${autoPageSeconds.roundToInt()}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = autoPageSeconds,
                    onValueChange = {
                        autoPageSeconds = it
                        AppSettings.setReaderAutoPage(context, it.roundToInt())
                    },
                    valueRange = 0f..30f,
                    steps = 5,
                    colors = SliderDefaults.colors(
                        thumbColor = primaryTeal,
                        activeTrackColor = primaryTeal
                    )
                )
            }

            // ─── 页面间隔 (上下模式) ────────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("页面间隔 (上下模式)", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Text(
                        text = "${pageIntervalPx.roundToInt()}px",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = pageIntervalPx,
                    onValueChange = {
                        pageIntervalPx = it
                        AppSettings.setReaderPageInterval(context, it.roundToInt())
                    },
                    valueRange = 0f..50f,
                    colors = SliderDefaults.colors(
                        thumbColor = primaryTeal,
                        activeTrackColor = primaryTeal
                    )
                )
            }

            // ─── 自定义屏幕亮度 ────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("自定义屏幕亮度", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Text(
                        text = if (customBrightness < 0f) "跟随系统" else "${customBrightness.roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = if (customBrightness < 0f) 0f else customBrightness,
                    onValueChange = {
                        customBrightness = it
                        AppSettings.setReaderCustomBrightness(context, it.roundToInt())
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = primaryTeal,
                        activeTrackColor = primaryTeal
                    )
                )
            }
        }
    }

    // ─── 阅读方向选择弹窗 ───────────────────────────────────────────────
    if (showDirectionDialog) {
        val options = listOf("从右向左", "从左向右", "上下连续模式")
        AlertDialog(
            onDismissRequest = { showDirectionDialog = false },
            title = { Text("阅读方向", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    options.forEach { text ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (text == readerDirection),
                                    onClick = {
                                        readerDirection = text
                                        AppSettings.setReaderDirection(context, text)
                                        showDirectionDialog = false
                                    }
                                )
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = (text == readerDirection),
                                onClick = {
                                    readerDirection = text
                                    AppSettings.setReaderDirection(context, text)
                                    showDirectionDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryTeal)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDirectionDialog = false }) {
                    Text("取消", color = primaryTeal)
                }
            }
        )
    }
}
