package com.example.ehviewer_scaffold.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import com.example.ehviewer_scaffold.MainActivity
import com.example.ehviewer_scaffold.ui.settings.AppSettings

// 可选主题定义：只保留 3 个
private data class ThemeOption(
    val name: String,
    val lightColor: Color,
    val darkColor: Color,
    val label: String
)

private val THEME_OPTIONS = listOf(
    ThemeOption("经典绿", Color(0xFF2E6B4F), Color(0xFF99D5B3), "经典绿"),
    ThemeOption("樱花粉", Color(0xFFC2185B), Color(0xFFFFAFCB), "樱花粉"),
    ThemeOption("静谧蓝", Color(0xFF1565C0), Color(0xFFAAC7FF), "静谧蓝"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? MainActivity

    // ── 读取当前设置 ────────────────────────────────────────────────────────
    var amoledBlack    by remember { mutableStateOf(AppSettings.isAmoledBlack(context)) }
    var followSystem   by remember { mutableStateOf(AppSettings.isThemeFollowSystem(context)) }
    var pixelShift     by remember { mutableStateOf(AppSettings.isPixelShift(context)) }
    var glassmorphism  by remember { mutableStateOf(AppSettings.isGlassmorphism(context)) }
    var hapticFeedback by remember { mutableStateOf(AppSettings.isHapticFeedback(context)) }
    var themeColor     by remember { mutableStateOf(AppSettings.getThemeColor(context)) }
    var listMode       by remember { mutableStateOf(AppSettings.getListMode(context)) }

    var showListModeDialog by remember { mutableStateOf(false) }

    // 主色跟随当前主题 primary
    val primaryColor  = MaterialTheme.colorScheme.primary
    val cardShape     = RoundedCornerShape(16.dp)
    val isDark        = MaterialTheme.colorScheme.background.red < 0.5f

    // 通用 Switch 颜色
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor  = Color.White,
        checkedTrackColor  = primaryColor
    )

    /** 保存某个外观设置后同步刷新 MainActivity 的主题状态 */
    fun saveAndRefresh() {
        activity?.refreshThemeSettings()
    }

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
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ══════════════════════════════════════════════════════════════
            // 1. 黑暗模式  (AMOLED 黑为最优先项)
            // ══════════════════════════════════════════════════════════════
            SectionLabel("黑暗模式")
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // 纯粹 AMOLED 黑 ── 最优先
                    SettingsSwitchRow(
                        title = "纯粹 AMOLED 黑",
                        subtitle = "深色模式下强制纯黑背景，OLED 屏极省电且防烧屏",
                        checked = amoledBlack,
                        switchColors = switchColors,
                        onCheckedChange = { v ->
                            amoledBlack = v
                            AppSettings.setAmoledBlack(context, v)
                            // AMOLED 开启时自动进入深色模式（关闭跟随系统）
                            if (v) {
                                followSystem = false
                                AppSettings.setThemeFollowSystem(context, false)
                            }
                            saveAndRefresh()
                        }
                    )
                    RowDivider()
                    // 跟随系统深色模式
                    SettingsSwitchRow(
                        title = "跟随系统深色模式",
                        subtitle = "自动根据系统深色/浅色模式切换主题",
                        checked = followSystem,
                        switchColors = switchColors,
                        onCheckedChange = { v ->
                            followSystem = v
                            AppSettings.setThemeFollowSystem(context, v)
                            saveAndRefresh()
                        }
                    )
                    RowDivider()
                    // 像素偏移防烧屏
                    SettingsSwitchRow(
                        title = "像素偏移（防烧屏引擎）",
                        subtitle = "每分钟微移 1–2 像素，防止固定图案长期点亮老化屏幕",
                        checked = pixelShift,
                        switchColors = switchColors,
                        onCheckedChange = { v ->
                            pixelShift = v
                            AppSettings.setPixelShift(context, v)
                        }
                    )
                }
            }

            // ══════════════════════════════════════════════════════════════
            // 2. 主题颜色 ── 3 个选项内联展示
            // ══════════════════════════════════════════════════════════════
            SectionLabel("主题颜色")
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    THEME_OPTIONS.forEach { opt ->
                        val selected = (themeColor == opt.name)
                        val swatch = if (isDark) opt.darkColor else opt.lightColor
                        ThemeColorChip(
                            label   = opt.label,
                            color   = swatch,
                            selected = selected,
                            onClick = {
                                themeColor = opt.name
                                AppSettings.setThemeColor(context, opt.name)
                                saveAndRefresh()
                            }
                        )
                    }
                }
            }

            // ══════════════════════════════════════════════════════════════
            // 3. 视觉效果
            // ══════════════════════════════════════════════════════════════
            SectionLabel("视觉效果")
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsSwitchRow(
                        title = "毛玻璃效果",
                        subtitle = "搜索栏、浮层等使用半透明磨砂玻璃质感（Glassmorphism）",
                        checked = glassmorphism,
                        switchColors = switchColors,
                        onCheckedChange = { v ->
                            glassmorphism = v
                            AppSettings.setGlassmorphism(context, v)
                        }
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        title = "触觉震动反馈",
                        subtitle = "在下拉刷新、长按标签等关键节点提供细腻震动确认感",
                        checked = hapticFeedback,
                        switchColors = switchColors,
                        onCheckedChange = { v ->
                            hapticFeedback = v
                            AppSettings.setHapticFeedback(context, v)
                        }
                    )
                }
            }

            // ══════════════════════════════════════════════════════════════
            // 4. 画廊列表
            // ══════════════════════════════════════════════════════════════
            SectionLabel("画廊列表")
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showListModeDialog = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            "列表模式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "当前：$listMode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = listMode,
                        style = MaterialTheme.typography.bodyMedium,
                        color = primaryColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // ── 列表模式选择弹窗 ─────────────────────────────────────────────────
    if (showListModeDialog) {
        val modes = listOf("瀑布流", "列表卡片", "紧凑网格")
        AlertDialog(
            onDismissRequest = { showListModeDialog = false },
            title = { Text("选择画廊列表模式", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    modes.forEach { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    listMode = m
                                    AppSettings.setListMode(context, m)
                                    showListModeDialog = false
                                }
                                .padding(vertical = 14.dp, horizontal = 4.dp)
                        ) {
                            RadioButton(
                                selected = (m == listMode),
                                onClick = {
                                    listMode = m
                                    AppSettings.setListMode(context, m)
                                    showListModeDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = primaryColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(m, style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showListModeDialog = false }) {
                    Text("取消", color = primaryColor)
                }
            }
        )
    }
}

// ─── 通用子组件 ────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    switchColors: SwitchColors,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = switchColors
        )
    }
}

@Composable
private fun ThemeColorChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
