package com.example.ehviewer_scaffold.ui.screens

import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ehviewer_scaffold.EhApplication
import com.example.ehviewer_scaffold.rust.EhRustBridge
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    
    var memberId by remember { mutableStateOf("") }
    var passHash by remember { mutableStateOf("") }
    var igneous by remember { mutableStateOf("") }
    var sk by remember { mutableStateOf("") }

    var passVisible by remember { mutableStateOf(false) }
    var igneousVisible by remember { mutableStateOf(false) }
    var skVisible by remember { mutableStateOf(false) }

    var isLoggedIn by remember { mutableStateOf(false) }
    var currentUid by remember { mutableStateOf<String?>(null) }
    var hasIgneous by remember { mutableStateOf(false) }
    var showEditForm by remember { mutableStateOf(false) }

    fun refreshLoginStatus() {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie("https://e-hentai.org") ?: ""
        val c2 = cm.getCookie("https://exhentai.org") ?: ""
        val c3 = cm.getCookie("https://forums.e-hentai.org") ?: ""
        val merged = "$c1; $c2; $c3"
        
        val loggedIn = merged.contains("ipb_member_id") && merged.contains("ipb_pass_hash")
        isLoggedIn = loggedIn

        fun extractCookieValue(cookies: String, key: String): String {
            val parts = cookies.split(";")
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.startsWith("$key=")) {
                    return trimmed.substring(key.length + 1)
                }
            }
            return ""
        }

        if (loggedIn) {
            val uid = extractCookieValue(merged, "ipb_member_id")
            currentUid = uid.ifBlank { null }
            hasIgneous = extractCookieValue(merged, "igneous").isNotBlank()
            showEditForm = false
        } else {
            currentUid = null
            hasIgneous = false
            showEditForm = true
        }
    }

    LaunchedEffect(Unit) {
        refreshLoginStatus()
    }

    // 智能解析并填充完整 Cookie 字符串
    fun parseAndFillFromRawCookie(raw: String) {
        if (raw.isBlank()) return
        val parts = raw.split(";")
        var filledCount = 0
        for (part in parts) {
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                when (key) {
                    "ipb_member_id" -> { memberId = value; filledCount++ }
                    "ipb_pass_hash" -> { passHash = value; filledCount++ }
                    "igneous" -> { igneous = value; filledCount++ }
                    "sk" -> { sk = value; filledCount++ }
                }
            }
        }
        if (filledCount > 0) {
            Toast.makeText(context, "已成功解析 $filledCount 个 Cookie 字段", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未识别到标准 Cookie 格式 (如 ipb_member_id=...)", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveAndLogin() {
        val trimmedMemberId = memberId.trim()
        val trimmedPassHash = passHash.trim()
        val trimmedIgneous = igneous.trim()
        val trimmedSk = sk.trim()

        if (trimmedMemberId.isBlank() || trimmedPassHash.isBlank()) {
            Toast.makeText(context, "请填写必填项 ipb_member_id 和 ipb_pass_hash", Toast.LENGTH_SHORT).show()
            return
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        
        fun setCookieForDomains(key: String, value: String) {
            val cookieString = "$key=$value; path=/; Max-Age=31536000"
            cm.setCookie("https://e-hentai.org", cookieString)
            cm.setCookie("https://exhentai.org", cookieString)
            cm.setCookie("https://forums.e-hentai.org", cookieString)
        }

        setCookieForDomains("ipb_member_id", trimmedMemberId)
        setCookieForDomains("ipb_pass_hash", trimmedPassHash)
        if (trimmedIgneous.isNotBlank()) setCookieForDomains("igneous", trimmedIgneous)
        if (trimmedSk.isNotBlank()) setCookieForDomains("sk", trimmedSk)
        
        cm.flush()
        
        val c1 = cm.getCookie("https://e-hentai.org") ?: ""
        val c2 = cm.getCookie("https://exhentai.org") ?: ""
        val merged = "$c1; $c2"
        
        scope.launch(Dispatchers.IO) {
            EhRustBridge.syncCookies(merged)
        }
        EhApplication.sharedCookieString = merged
        // Mirror it so a cold start can replay the session into Rust — without that,
        // every restart loses it and the signed-in pages answer "not logged in".
        AppSettings.setSessionCookie(context, merged)

        Toast.makeText(context, "Cookie 保存成功并已登录", Toast.LENGTH_SHORT).show()
        // 清空表单状态，保护敏感信息不留痕
        memberId = ""
        passHash = ""
        igneous = ""
        sk = ""
        refreshLoginStatus()
    }
    
    fun logout() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        scope.launch(Dispatchers.IO) {
            EhRustBridge.syncCookies("")
        }
        EhApplication.sharedCookieString = ""
        AppSettings.setSessionCookie(context, "")
        memberId = ""
        passHash = ""
        igneous = ""
        sk = ""
        refreshLoginStatus()
        Toast.makeText(context, "已退出登录并彻底清空本地 Cookie", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cookie 账号管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── 登录状态与已登录信息卡片 ───────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isLoggedIn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isLoggedIn) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector = if (isLoggedIn) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (isLoggedIn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isLoggedIn) "已登录 E-Hentai 账号" else "未登录",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isLoggedIn) "UID: ${currentUid ?: "未知"}" else "暂无保存的登录 Cookie",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isLoggedIn) {
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "ExHentai 里站权限 (igneous)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (hasIgneous) "已就绪 ✓" else "未配置",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = if (hasIgneous) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { showEditForm = !showEditForm },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (showEditForm) Icons.Default.ExpandLess else Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (showEditForm) "收起配置" else "更新 / 替换")
                            }

                            Button(
                                onClick = { logout() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("退出登录")
                            }
                        }
                    }
                }
            }

            // ── Cookie 填写与编辑表单（仅在未登录或用户点击更新时展开）────────
            AnimatedVisibility(visible = !isLoggedIn || showEditForm) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 快捷解析提示与按钮
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "支持直接复制浏览器全部 Cookie 字符串一键智能解析（如 ipb_member_id=...; ipb_pass_hash=...; igneous=...）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text.orEmpty()
                                    parseAndFillFromRawCookie(clipText)
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("从剪贴板一键智能解析 Cookie", fontSize = 13.sp)
                            }
                        }
                    }

                    // Form Fields
                    SecureCookieField(
                        label = "ipb_member_id (用户 UID) *",
                        value = memberId,
                        onValueChange = { memberId = it },
                        placeholder = "例如：1234567",
                        isSecret = false,
                        isVisible = true,
                        onToggleVisibility = {}
                    )
                    
                    SecureCookieField(
                        label = "ipb_pass_hash (登录凭证哈希) *",
                        value = passHash,
                        onValueChange = { passHash = it },
                        placeholder = "例如：a1b2c3d4e5f6...",
                        isSecret = true,
                        isVisible = passVisible,
                        onToggleVisibility = { passVisible = !passVisible }
                    )
                    
                    SecureCookieField(
                        label = "igneous (ExHentai 里站通行证)",
                        value = igneous,
                        onValueChange = { igneous = it },
                        placeholder = "可选，用于访问里站",
                        isSecret = true,
                        isVisible = igneousVisible,
                        onToggleVisibility = { igneousVisible = !igneousVisible }
                    )
                    
                    SecureCookieField(
                        label = "sk (安全凭据)",
                        value = sk,
                        onValueChange = { sk = it },
                        placeholder = "可选，部分功能验证",
                        isSecret = true,
                        isVisible = skVisible,
                        onToggleVisibility = { skVisible = !skVisible }
                    )

                    Button(
                        onClick = { saveAndLogin() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                    ) {
                        Text("保存并应用 Cookie", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecureCookieField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isSecret: Boolean = false,
    isVisible: Boolean = false,
    onToggleVisibility: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (label.contains("*")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            singleLine = true,
            visualTransformation = if (isSecret && !isVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isSecret) KeyboardType.Password else KeyboardType.Text),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    if (isSecret) {
                        IconButton(onClick = onToggleVisibility, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "切换可见性",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text.orEmpty().trim()
                            if (clip.isNotEmpty()) {
                                onValueChange(clip)
                                Toast.makeText(context, "已粘贴", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "粘贴",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        )
    }
}
