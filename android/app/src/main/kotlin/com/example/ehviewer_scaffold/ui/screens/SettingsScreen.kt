package com.example.ehviewer_scaffold.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToReaderSettings: () -> Unit,
    onNavigateToAppearanceSettings: () -> Unit,
    onNavigateToDownloadSettings: () -> Unit,
    onNavigateToWebConfig: () -> Unit,
    onNavigateToSearchSettings: () -> Unit,
    onNavigateToAdvancedSettings: () -> Unit,
    onNavigateToSecuritySettings: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isExHentai by remember { mutableStateOf(AppSettings.isExHentai(context)) }
    var showJapaneseTitle by remember { mutableStateOf(AppSettings.isShowJapaneseTitle(context)) }
    var showTagTranslations by remember { mutableStateOf(AppSettings.isShowTagTranslations(context)) }

    var showAboutDialog by remember { mutableStateOf(false) }

    val primaryGreen = Color(0xFF4CAF50)
    val cardShape = RoundedCornerShape(16.dp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
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
            // ─── 1. E-HENTAI 站点 ───────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "E-HENTAI 站点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // 画廊站点切换胶囊
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Text("画廊站点", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)

                            // E-Hentai / ExHentai 分段按钮
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    selected = !isExHentai,
                                    onClick = {
                                        if (isExHentai) {
                                            isExHentai = false
                                            AppSettings.setExHentai(context, false)
                                            Toast.makeText(context, "已切换为 E-Hentai 站点", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                    icon = {}
                                ) {
                                    Text("E-Hentai")
                                }
                                SegmentedButton(
                                    selected = isExHentai,
                                    onClick = {
                                        if (!isExHentai) {
                                            isExHentai = true
                                            AppSettings.setExHentai(context, true)
                                            Toast.makeText(context, "已切换为 ExHentai 里站 (需要 Cookie 登录)", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                    icon = {}
                                ) {
                                    Text("ExHentai")
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // Cookie 登录
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onNavigateToLogin)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Cookie 登录", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("设置账号凭证以访问受限内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // E-Hentai 网站设置
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onNavigateToWebConfig)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("E-Hentai 网站设置", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("在线管理 H@H、图片分辨率、标题等 (uconfig.php)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // ─── 2. 显示设置 ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "显示设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // 显示日文标题
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("显示日文标题", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("画廊详情优先显示日文原标题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(
                                checked = showJapaneseTitle,
                                onCheckedChange = {
                                    showJapaneseTitle = it
                                    AppSettings.setShowJapaneseTitle(context, it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = primaryGreen)
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // 显示标签翻译
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("显示标签翻译", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("在详情页和画廊列表显示中文标签", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(
                                checked = showTagTranslations,
                                onCheckedChange = { enabled ->
                                    showTagTranslations = enabled
                                    AppSettings.setShowTagTranslations(context, enabled)
                                    if (enabled) {
                                        val tagDb = java.io.File(context.filesDir, "tag_db.json")
                                        if (!tagDb.exists()) {
                                            Toast.makeText(context, "已开启标签翻译，正在后台下载标签翻译数据库...", Toast.LENGTH_LONG).show()
                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                val ok = EhRustBridge.downloadTagDb(tagDb.absolutePath)
                                                if (ok) {
                                                    EhRustBridge.loadTagDb(tagDb.absolutePath)
                                                }
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        if (ok) "标签翻译数据库下载完毕" else "已启用标签翻译模式",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        } else {
                                            Toast.makeText(context, "已启用标签翻译", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "已关闭标签翻译，恢复默认英文标签", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = primaryGreen)
                            )
                        }
                    }
                }
            }

            // ─── 3. 应用功能 ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "应用功能",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // 阅读设置
                        SettingsFeatureItem(
                            icon = Icons.Default.MenuBook,
                            iconBgColor = Color(0xFFE3F2FD),
                            iconColor = Color(0xFF1E88E5),
                            title = "阅读设置",
                            onClick = onNavigateToReaderSettings
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // 外观设置
                        SettingsFeatureItem(
                            icon = Icons.Default.Palette,
                            iconBgColor = Color(0xFFF3E5F5),
                            iconColor = Color(0xFFAB47BC),
                            title = "外观设置",
                            onClick = onNavigateToAppearanceSettings
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // 下载设置
                        SettingsFeatureItem(
                            icon = Icons.Default.Download,
                            iconBgColor = Color(0xFFE0F2F1),
                            iconColor = Color(0xFF00897B),
                            title = "下载设置",
                            onClick = onNavigateToDownloadSettings
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // 搜索设置
                        SettingsFeatureItem(
                            icon = Icons.Default.Search,
                            iconBgColor = Color(0xFFFFF3E0),
                            iconColor = Color(0xFFFB8C00),
                            title = "搜索设置",
                            onClick = onNavigateToSearchSettings
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // 高级设置
                        SettingsFeatureItem(
                            icon = Icons.Default.Tune,
                            iconBgColor = Color(0xFFECEFF1),
                            iconColor = Color(0xFF546E7A),
                            title = "高级设置",
                            onClick = onNavigateToAdvancedSettings
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        // 安全与隐私
                        SettingsFeatureItem(
                            icon = Icons.Default.Security,
                            iconBgColor = Color(0xFFFFEBEE),
                            iconColor = Color(0xFFE53935),
                            title = "安全与隐私",
                            onClick = onNavigateToSecuritySettings
                        )
                    }
                }
            }

            // ─── 4. 关于 ───────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "关于",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )

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
                            .clickable { showAboutDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFE8EAF6))
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF3F51B5), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Text("关于 EH Viewer (Eh-ru)", style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }

    // ─── 关于弹窗 ─────────────────────────────────────────────────────────
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Eh-ru", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("版本：1.0.0 (Release arm64-v8a)")
                    Text("技术架构：Android Kotlin + Jetpack Compose + Rust Tokio Core")
                    Text("原生特性：16KB 内存分页对齐、Coil+Rust 统一缓存、纯 64 位高效运行")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("关闭") }
            }
        )
    }
}

/**
 * 设置项卡片中的功能行组件
 */
@Composable
fun SettingsFeatureItem(
    icon: ImageVector,
    iconBgColor: Color,
    iconColor: Color,
    title: String,
    onClick: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconBgColor)
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(13.dp)
        )
    }
}
