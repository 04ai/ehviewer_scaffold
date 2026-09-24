package com.example.ehviewer_scaffold.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ehviewer_scaffold.ui.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EhWebConfigScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewInstance: WebView? = null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("E-Hentai 网站设置 (uconfig.php)", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    webViewInstance = this

                    // Follow the site setting. This was hard-coded to
                    // e-hentai.org, so an ExHentai user always landed on the
                    // wrong host and hit a login wall. Cookies come from the
                    // process-wide CookieManager, so the session carries over.
                    val host = if (AppSettings.isExHentai(ctx)) {
                        "https://exhentai.org"
                    } else {
                        "https://e-hentai.org"
                    }
                    loadUrl("$host/uconfig.php")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}
