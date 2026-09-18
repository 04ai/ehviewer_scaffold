package com.example.ehviewer_scaffold.ui.screens

import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ehviewer_scaffold.rust.EhRustBridge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    var memberId by remember { mutableStateOf("") }
    var passHash by remember { mutableStateOf("") }
    var igneous by remember { mutableStateOf("") }
    var sk by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }

    // Check login status on launch
    var isLoggedIn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie("https://e-hentai.org") ?: ""
        val c2 = cm.getCookie("https://exhentai.org") ?: ""
        val merged = "$c1; $c2"
        
        isLoggedIn = merged.contains("ipb_member_id") && merged.contains("ipb_pass_hash")
        
        // Parse existing cookies to populate fields
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
        memberId = extractCookieValue(merged, "ipb_member_id")
        passHash = extractCookieValue(merged, "ipb_pass_hash")
        igneous = extractCookieValue(merged, "igneous")
        sk = extractCookieValue(merged, "sk")
    }

    fun saveAndLogin() {
        if (memberId.isBlank() || passHash.isBlank()) {
            Toast.makeText(context, "请填写必填项 ipb_member_id 和 ipb_pass_hash", Toast.LENGTH_SHORT).show()
            return
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        
        // Helper to set cookie for multiple domains
        fun setCookieForDomains(key: String, value: String) {
            val cookieString = "$key=$value; path=/; Max-Age=31536000"
            cm.setCookie("https://e-hentai.org", cookieString)
            cm.setCookie("https://exhentai.org", cookieString)
            cm.setCookie("https://forums.e-hentai.org", cookieString)
        }

        setCookieForDomains("ipb_member_id", memberId.trim())
        setCookieForDomains("ipb_pass_hash", passHash.trim())
        if (igneous.isNotBlank()) setCookieForDomains("igneous", igneous.trim())
        if (sk.isNotBlank()) setCookieForDomains("sk", sk.trim())
        
        cm.flush()
        
        // Re-read merged to sync to Rust
        val c1 = cm.getCookie("https://e-hentai.org") ?: ""
        val c2 = cm.getCookie("https://exhentai.org") ?: ""
        val merged = "$c1; $c2"
        
        EhRustBridge.syncCookies(merged)
        com.example.ehviewer_scaffold.EhApplication.sharedCookieString = merged
        
        Toast.makeText(context, "Cookie 已保存并同步", Toast.LENGTH_SHORT).show()
        isLoggedIn = true
    }
    
    fun logout() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        EhRustBridge.syncCookies("")
        com.example.ehviewer_scaffold.EhApplication.sharedCookieString = ""
        memberId = ""
        passHash = ""
        igneous = ""
        sk = ""
        isLoggedIn = false
        Toast.makeText(context, "已退出登录并清除所有 Cookie", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cookie 登录", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Status Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (isLoggedIn) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Status",
                        tint = if (isLoggedIn) Color(0xFF4CAF50) else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isLoggedIn) "已登录" else "未登录",
                        color = if (isLoggedIn) Color(0xFF4CAF50) else Color.Gray,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Description Text
            Text(
                text = "从浏览器复制 e-hentai.org 的 Cookie 值填入下方。\n" +
                       "ipb_member_id 和 ipb_pass_hash 为必填项。\n" +
                       "填写 igneous 可访问 ExHentai（前提是账号已开通）。",
                color = Color.Gray,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            // Form Fields
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CookieTextField(
                    label = "ipb_member_id *",
                    value = memberId,
                    onValueChange = { memberId = it },
                    onPaste = { memberId = clipboardManager.getText()?.text ?: memberId }
                )
                
                CookieTextField(
                    label = "ipb_pass_hash *",
                    value = passHash,
                    onValueChange = { passHash = it },
                    isPassword = true,
                    passVisible = passVisible,
                    onTogglePassword = { passVisible = !passVisible },
                    onPaste = { passHash = clipboardManager.getText()?.text ?: passHash }
                )
                
                CookieTextField(
                    label = "igneous",
                    value = igneous,
                    onValueChange = { igneous = it },
                    onPaste = { igneous = clipboardManager.getText()?.text ?: igneous }
                )
                
                CookieTextField(
                    label = "sk",
                    value = sk,
                    onValueChange = { sk = it },
                    onPaste = { sk = clipboardManager.getText()?.text ?: sk }
                )
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { saveAndLogin() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
                ) {
                    Text("保存并登录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(
                    onClick = { logout() }
                ) {
                    Text("退出登录", color = Color(0xFFD32F2F), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun CookieTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    passVisible: Boolean = false,
    onTogglePassword: () -> Unit = {},
    onPaste: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (label.contains("*")) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onBackground
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                unfocusedContainerColor = Color(0xFFE0E0E0).copy(alpha = 0.5f),
                focusedContainerColor = Color(0xFFE0E0E0).copy(alpha = 0.5f)
            ),
            singleLine = true,
            visualTransformation = if (isPassword && !passVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    if (isPassword) {
                        IconButton(onClick = onTogglePassword, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (passVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Visibility",
                                tint = Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (value.isNotEmpty()) {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(value))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onPaste,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )
    }
}
