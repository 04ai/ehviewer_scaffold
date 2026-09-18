package com.example.ehviewer_scaffold.ui.screens

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
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
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val tealGreen = Color(0xFF00695C)

    var biometricUnlock by remember { mutableStateOf(AppSettings.isBiometricUnlock(context)) }
    var blurRecent by remember { mutableStateOf(AppSettings.isBlurInRecentTasks(context)) }

    fun applyRecentBlur(enabled: Boolean) {
        activity?.let {
            if (enabled) {
                it.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            // 指纹/面容解锁
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !biometricUnlock
                        biometricUnlock = next
                        AppSettings.setBiometricUnlock(context, next)
                        Toast.makeText(
                            context,
                            if (next) "已开启生物识别保护" else "已关闭生物识别保护",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "指纹/面容解锁",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "开启后，每次进入 App 需验证生物信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = biometricUnlock,
                    onCheckedChange = {
                        biometricUnlock = it
                        AppSettings.setBiometricUnlock(context, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = tealGreen
                    )
                )
            }

            // 在最近任务中模糊界面
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !blurRecent
                        blurRecent = next
                        AppSettings.setBlurInRecentTasks(context, next)
                        applyRecentBlur(next)
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "在最近任务中模糊界面",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "防止应用在后台多任务卡片中泄露隐私",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Switch(
                    checked = blurRecent,
                    onCheckedChange = {
                        blurRecent = it
                        AppSettings.setBlurInRecentTasks(context, it)
                        applyRecentBlur(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = tealGreen
                    )
                )
            }

            // 立即锁定应用
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        Toast.makeText(context, "应用已锁定，重新进入需身份验证", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "立即锁定应用",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "手动进入锁定状态，需要验证才能继续",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
