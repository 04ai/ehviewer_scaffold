package com.example.ehviewer_scaffold.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tealGreen = Color(0xFF00695C)

    var searchIncludeName by remember { mutableStateOf(AppSettings.isSearchIncludeName(context)) }
    var searchIncludeTags by remember { mutableStateOf(AppSettings.isSearchIncludeTags(context)) }
    var searchIncludeDesc by remember { mutableStateOf(AppSettings.isSearchIncludeDesc(context)) }
    var defaultCategory by remember { mutableStateOf(AppSettings.getDefaultSearchCategory(context)) }
    var blockedTags by remember { mutableStateOf(AppSettings.getBlockedTags(context)) }

    var showCategoryDialog by remember { mutableStateOf(false) }
    var showBlockedTagsDialog by remember { mutableStateOf(false) }
    var newTagInput by remember { mutableStateOf("") }

    val categories = listOf("跟随主页", "全部", "同人志", "漫画", "游戏CG", "欧美", "原创", "动漫Cosplay")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            // 搜索时包含画廊名称
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !searchIncludeName
                        searchIncludeName = next
                        AppSettings.setSearchIncludeName(context, next)
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "搜索时包含画廊名称",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp
                )
                Switch(
                    checked = searchIncludeName,
                    onCheckedChange = {
                        searchIncludeName = it
                        AppSettings.setSearchIncludeName(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = tealGreen
                    )
                )
            }

            // 搜索时包含标签
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !searchIncludeTags
                        searchIncludeTags = next
                        AppSettings.setSearchIncludeTags(context, next)
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "搜索时包含标签",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp
                )
                Switch(
                    checked = searchIncludeTags,
                    onCheckedChange = {
                        searchIncludeTags = it
                        AppSettings.setSearchIncludeTags(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = tealGreen
                    )
                )
            }

            // 搜索时包含描述
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !searchIncludeDesc
                        searchIncludeDesc = next
                        AppSettings.setSearchIncludeDesc(context, next)
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "搜索时包含描述",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp
                )
                Switch(
                    checked = searchIncludeDesc,
                    onCheckedChange = {
                        searchIncludeDesc = it
                        AppSettings.setSearchIncludeDesc(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = tealGreen
                    )
                )
            }

            // 默认搜索类别
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCategoryDialog = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "默认搜索类别",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "搜索将只在主页当前点亮的主标签内进行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = defaultCategory,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            // 屏蔽标签管理
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showBlockedTagsDialog = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "屏蔽标签管理",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "已屏蔽 ${blockedTags.size} 个标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    // 默认搜索类别选择弹窗
    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("选择默认搜索类别", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultCategory = cat
                                    AppSettings.setDefaultSearchCategory(context, cat)
                                    showCategoryDialog = false
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = defaultCategory == cat,
                                onClick = {
                                    defaultCategory = cat
                                    AppSettings.setDefaultSearchCategory(context, cat)
                                    showCategoryDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = tealGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(cat, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text("取消") }
            }
        )
    }

    // 屏蔽标签管理弹窗
    if (showBlockedTagsDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedTagsDialog = false },
            title = { Text("屏蔽标签管理", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newTagInput,
                            onValueChange = { newTagInput = it },
                            placeholder = { Text("输入屏蔽标签 (如 guro)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val tag = newTagInput.trim().lowercase()
                                if (tag.isNotEmpty()) {
                                    AppSettings.addBlockedTag(context, tag)
                                    blockedTags = AppSettings.getBlockedTags(context)
                                    newTagInput = ""
                                    Toast.makeText(context, "已添加屏蔽标签", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = tealGreen)
                        ) {
                            Text("添加")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (blockedTags.isEmpty()) {
                        Text(
                            "暂无屏蔽标签。添加后包含该标签的画廊将被自动过滤。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            blockedTags.forEach { tag ->
                                InputChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text(tag) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                AppSettings.removeBlockedTag(context, tag)
                                                blockedTags = AppSettings.getBlockedTags(context)
                                            },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "删除",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlockedTagsDialog = false }) { Text("完成") }
            }
        )
    }
}
