package com.example.ehviewer_scaffold.ui.screens

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.ehviewer_scaffold.ui.settings.AppSettings

/** 递归解包 ContextWrapper 获取顶层 Activity 引用 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 调用 BiometricPrompt 执行一次验证，回调 onSuccess / onFail */
private fun launchBiometricPrompt(
    context: Context,
    onSuccess: () -> Unit,
    onFail: (String) -> Unit
) {
    val activity = context.findActivity() as? FragmentActivity ?: run {
        onFail("需要 FragmentActivity 环境")
        return
    }
    val biometricManager = BiometricManager.from(context)
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
        onFail("设备未设置生物识别或屏幕锁定，请先在系统设置中配置")
        return
    }
    val executor = ContextCompat.getMainExecutor(context)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                onFail(errString.toString())
            }
        }
        override fun onAuthenticationFailed() {
            onFail("验证失败，请重试")
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("身份验证")
        .setSubtitle("使用生物识别或屏幕锁定解锁 Eh-ru")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()
    prompt.authenticate(info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(16.dp)

    var biometricUnlock by remember { mutableStateOf(AppSettings.isBiometricUnlock(context)) }
    var blurRecent by remember { mutableStateOf(AppSettings.isBlurInRecentTasks(context)) }

    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
        checkedTrackColor = MaterialTheme.colorScheme.primary
    )

    fun applyRecentBlur(enabled: Boolean) {
        val act = context.findActivity()
        act?.let {
            if (enabled) {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全与隐私", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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

            // ── 应用锁定 ────────────────────────────────────────────────────
            SectionLabelSec("应用锁定")
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // 指纹/面容解锁
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "指纹 / 面容解锁",
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "开启后，每次进入 App 需验证生物信息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Switch(
                            checked = biometricUnlock,
                            onCheckedChange = { wantEnable ->
                                if (wantEnable) {
                                    // 开启前先验证一次，确保用户本人操作
                                    launchBiometricPrompt(
                                        context = context,
                                        onSuccess = {
                                            biometricUnlock = true
                                            AppSettings.setBiometricUnlock(context, true)
                                            Toast.makeText(context, "已开启生物识别保护", Toast.LENGTH_SHORT).show()
                                        },
                                        onFail = { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    biometricUnlock = false
                                    AppSettings.setBiometricUnlock(context, false)
                                    Toast.makeText(context, "已关闭生物识别保护", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = switchColors
                        )
                    }
                }
            }

            // ── 隐私保护 ────────────────────────────────────────────────────
            SectionLabelSec("隐私保护")
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
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            "在最近任务中隐藏截图",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "防止应用在后台多任务卡片中泄露隐私内容",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Switch(
                        checked = blurRecent,
                        onCheckedChange = {
                            blurRecent = it
                            AppSettings.setBlurInRecentTasks(context, it)
                            applyRecentBlur(it)
                        },
                        colors = switchColors
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabelSec(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp)
    )
}
