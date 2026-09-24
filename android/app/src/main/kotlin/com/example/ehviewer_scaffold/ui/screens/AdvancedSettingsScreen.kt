package com.example.ehviewer_scaffold.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tealGreen = Color(0xFF00695C)
    val lightTealBg = Color(0xFFD7ECEF)

    var showTagTranslations by remember { mutableStateOf(AppSettings.isShowTagTranslations(context)) }
    var autoCleanExpired by remember { mutableStateOf(AppSettings.isAutoCleanExpiredCache(context)) }
    var cleanPeriodDays by remember { mutableStateOf(AppSettings.getAutoCleanPeriodDays(context)) }

    var cacheSizeMbText by remember { mutableStateOf("计算中...") }
    var isUpdatingDb by remember { mutableStateOf(false) }

    // 计算缓存大小函数
    fun calculateCacheSize() {
        scope.launch(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            var totalBytes = 0L
            try {
                cacheDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        totalBytes += file.length()
                    }
                }
            } catch (_: Exception) {}
            val mb = totalBytes.toDouble() / (1024 * 1024)
            val formatted = String.format(Locale.US, "%.1f MB", mb)
            withContext(Dispatchers.Main) {
                cacheSizeMbText = formatted
            }
        }
    }

    LaunchedEffect(Unit) {
        calculateCacheSize()
    }

    // NOTE: the "auto clean expired cache" switch is intentionally *not* acted
    // on here any more. It used to sweep on screen entry, which meant the
    // switch only did anything for users who happened to open this page.
    // The sweep now runs once per cold start in EhApplication, where the name
    // "automatic" actually applies; the button further down stays manual.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("高级", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
        ) {
            // 显示标签翻译
            fun handleTagTranslationsToggle(enabled: Boolean) {
                showTagTranslations = enabled
                AppSettings.setShowTagTranslations(context, enabled)
                if (enabled) {
                    val tagDb = File(context.filesDir, "tag_db.json")
                    if (!tagDb.exists()) {
                        Toast.makeText(context, "已开启标签翻译，正在后台下载标签翻译数据库...", Toast.LENGTH_LONG).show()
                        scope.launch(Dispatchers.IO) {
                            val ok = EhRustBridge.downloadTagDb(tagDb.absolutePath)
                            if (ok) {
                                EhRustBridge.loadTagDb(tagDb.absolutePath)
                            }
                            withContext(Dispatchers.Main) {
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
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        handleTagTranslationsToggle(!showTagTranslations)
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "显示标签翻译",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "将英文标签翻译为中文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = showTagTranslations,
                    onCheckedChange = { handleTagTranslationsToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = tealGreen
                    )
                )
            }

            // 更新标签翻译数据
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isUpdatingDb) {
                        isUpdatingDb = true
                        scope.launch(Dispatchers.IO) {
                            val dbFile = File(context.filesDir, "tag_db.json")
                            val ok = EhRustBridge.downloadTagDb(dbFile.absolutePath)
                            if (ok) {
                                EhRustBridge.loadTagDb(dbFile.absolutePath)
                            }
                            withContext(Dispatchers.Main) {
                                isUpdatingDb = false
                                Toast.makeText(
                                    context,
                                    if (ok) "标签翻译数据库已更新完毕" else "已同步内置中文标签库",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "更新标签翻译数据",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isUpdatingDb) "正在下载并解析最新数据库..." else "从 Github 下载最新的 EhTagTranslation 数据库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                if (isUpdatingDb) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            // 清除图片缓存
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch(Dispatchers.IO) {
                            // 1. Tell Rust to flush its L1 memory cache and delete all disk cache files
                            //    (pass 0 days so ALL files are cleared regardless of age)
                            EhRustBridge.clearExpiredCache(0)
                            // 2. Also wipe any other files in the Android cacheDir (e.g. OkHttp, WebView)
                            try {
                                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            } catch (_: Exception) {}
                            calculateCacheSize()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "深度清理完成，图片缓存已清空", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "清除图片缓存",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "当前大小: $cacheSizeMbText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "深度清理将释放所有磁盘空间 (不影响已下载内容)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }

            // 自动清理过期缓存
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !autoCleanExpired
                        autoCleanExpired = next
                        AppSettings.setAutoCleanExpiredCache(context, next)
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "自动清理过期缓存",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "到达设定的天数后，App 将在后台自动清理旧图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = autoCleanExpired,
                    onCheckedChange = {
                        autoCleanExpired = it
                        AppSettings.setAutoCleanExpiredCache(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = tealGreen
                    )
                )
            }

            // 自动清理周期
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "自动清理周期",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 分段选择按钮: 3天 | ✓ 7天 | 15天 | 30天
                val periods = listOf(3, 7, 15, 30)
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        periods.forEachIndexed { index, days ->
                            val isSelected = cleanPeriodDays == days
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(
                                        when (index) {
                                            0 -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                                            periods.lastIndex -> RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                                            else -> RoundedCornerShape(0.dp)
                                        }
                                    )
                                    .background(if (isSelected) lightTealBg else Color.Transparent)
                                    .clickable {
                                        cleanPeriodDays = days
                                        AppSettings.setAutoCleanPeriodDays(context, days)
                                        // Immediately trigger a sweep with the new period if auto-clean is on
                                        if (autoCleanExpired) {
                                            scope.launch(Dispatchers.IO) {
                                                val freed = EhRustBridge.clearExpiredCache(days)
                                                if (freed >= 0) calculateCacheSize()
                                            }
                                        }
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color(0xFF004D40),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = "${days}天",
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFF004D40) else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            if (index < periods.lastIndex) {
                                VerticalDivider(
                                    modifier = Modifier.fillMaxHeight(),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            // 导出应用数据
            var exportStatus by remember { mutableStateOf("") }
            val exportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch(Dispatchers.IO) {
                    try {
                        val prefs = context.getSharedPreferences("eh_app_settings", android.content.Context.MODE_PRIVATE)
                        val json = JSONObject(prefs.all.mapValues { it.value.toString() })
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(json.toString(2).toByteArray())
                        }
                        withContext(Dispatchers.Main) {
                            exportStatus = "备份成功"
                            Toast.makeText(context, "已成功导出备份文件", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "导出失败：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                        exportLauncher.launch("ehru_backup_$timestamp.json")
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "导出应用数据",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (exportStatus.isNotEmpty()) exportStatus else "将所有设置导出为 JSON 备份文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exportStatus.isNotEmpty()) tealGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }

            // 导入应用数据
            var importStatus by remember { mutableStateOf("") }
            val importLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch(Dispatchers.IO) {
                    try {
                        val jsonStr = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                        val json = JSONObject(jsonStr)
                        val prefs = context.getSharedPreferences("eh_app_settings", android.content.Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        val keys = json.keys()
                        var count = 0
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = json.getString(key)
                            // Try to restore as the correct type by peeking at current value type
                            when (prefs.all[key]) {
                                is Boolean -> editor.putBoolean(key, value.toBooleanStrictOrNull() ?: value == "true")
                                is Int -> editor.putInt(key, value.toIntOrNull() ?: 0)
                                is Float -> editor.putFloat(key, value.toFloatOrNull() ?: 0f)
                                is Long -> editor.putLong(key, value.toLongOrNull() ?: 0L)
                                else -> editor.putString(key, value)
                            }
                            count++
                        }
                        editor.apply()
                        withContext(Dispatchers.Main) {
                            importStatus = "已恢复 $count 项设置"
                            Toast.makeText(context, "备份导入成功，重启 App 后完全生效", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "导入失败：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        importLauncher.launch(arrayOf("application/json", "*/*"))
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "导入应用数据",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (importStatus.isNotEmpty()) importStatus else "从 JSON 备份文件恢复设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importStatus.isNotEmpty()) tealGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}
