package com.example.ehviewer_scaffold.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var followSystem by remember { mutableStateOf(AppSettings.isThemeFollowSystem(context)) }
    var amoledBlack by remember { mutableStateOf(AppSettings.isAmoledBlack(context)) }
    var pixelShift by remember { mutableStateOf(AppSettings.isPixelShift(context)) }
    var glassmorphism by remember { mutableStateOf(AppSettings.isGlassmorphism(context)) }
    var hapticFeedback by remember { mutableStateOf(AppSettings.isHapticFeedback(context)) }
    var themeColor by remember { mutableStateOf(AppSettings.getThemeColor(context)) }
    var listMode by remember { mutableStateOf(AppSettings.getListMode(context)) }

    var showColorDialog by remember { mutableStateOf(false) }
    var showListModeDialog by remember { mutableStateOf(false) }

    val primaryTeal = Color(0xFF00796B)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // ─── 跟随系统深色模式 ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("跟随系统深色模式", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Switch(
                    checked = followSystem,
                    onCheckedChange = {
                        followSystem = it
                        AppSettings.setThemeFollowSystem(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 纯粹 AMOLED 黑 ────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("纯粹 AMOLED 黑", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "针对 OLED 屏幕优化，深色模式下使用纯黑背景防烧屏且极度省电",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = amoledBlack,
                    onCheckedChange = {
                        amoledBlack = it
                        AppSettings.setAmoledBlack(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 像素偏移 (防烧屏引擎) ─────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("像素偏移 (防烧屏引擎)", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "每隔一分钟让全界面极其缓慢地微动 1-2 像素，防止固定图案长期点亮老化屏幕",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = pixelShift,
                    onCheckedChange = {
                        pixelShift = it
                        AppSettings.setPixelShift(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 毛玻璃效果 ────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("毛玻璃效果", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "搜索栏、阅读器浮层等使用半透明磨砂玻璃质感 (Glassmorphism)，关闭则恢复纯色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = glassmorphism,
                    onCheckedChange = {
                        glassmorphism = it
                        AppSettings.setGlassmorphism(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 触觉震动反馈 ──────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("触觉震动反馈", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "在下拉刷新、长按标签等关键节点提供微弱且现代的物理震动确认感",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = hapticFeedback,
                    onCheckedChange = {
                        hapticFeedback = it
                        AppSettings.setHapticFeedback(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = primaryTeal
                    )
                )
            }

            // ─── 主题颜色 ──────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showColorDialog = true }
                    .padding(vertical = 4.dp)
            ) {
                Text("主题颜色", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Text(
                    text = themeColor,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ─── 列表模式 ──────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showListModeDialog = true }
                    .padding(vertical = 4.dp)
            ) {
                Text("列表模式", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                Text(
                    text = listMode,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ─── 主题颜色选择弹窗 ───────────────────────────────────────────────
    if (showColorDialog) {
        val colors = listOf("纯净白", "经典绿", "暗夜黑", "樱花粉", "静谧蓝")
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("选择主题颜色", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    colors.forEach { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (c == themeColor),
                                    onClick = {
                                        themeColor = c
                                        AppSettings.setThemeColor(context, c)
                                        showColorDialog = false
                                    }
                                )
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = (c == themeColor),
                                onClick = {
                                    themeColor = c
                                    AppSettings.setThemeColor(context, c)
                                    showColorDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryTeal)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(c, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorDialog = false }) {
                    Text("取消", color = primaryTeal)
                }
            }
        )
    }

    // ─── 列表模式选择弹窗 ───────────────────────────────────────────────
    if (showListModeDialog) {
        val modes = listOf("瀑布流", "列表卡片", "紧凑网格")
        AlertDialog(
            onDismissRequest = { showListModeDialog = false },
            title = { Text("选择画廊列表显示模式", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    modes.forEach { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (m == listMode),
                                    onClick = {
                                        listMode = m
                                        AppSettings.setListMode(context, m)
                                        showListModeDialog = false
                                    }
                                )
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = (m == listMode),
                                onClick = {
                                    listMode = m
                                    AppSettings.setListMode(context, m)
                                    showListModeDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryTeal)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(m, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showListModeDialog = false }) {
                    Text("取消", color = primaryTeal)
                }
            }
        )
    }
}
