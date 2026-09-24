package com.example.ehviewer_scaffold.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 根据主题名称和当前深色/AMOLED 模式选取 ColorScheme。
 * - amoledBlack 优先级最高：深色模式下强制纯黑背景。
 * - themeColorName: "经典绿" | "樱花粉" | "静谧蓝"
 */
private fun resolveColorScheme(
    themeColorName: String,
    darkTheme: Boolean,
    amoledBlack: Boolean
): ColorScheme {
    return when (themeColorName) {
        "樱花粉" -> when {
            darkTheme && amoledBlack -> PinkAmoledScheme
            darkTheme                -> PinkDarkScheme
            else                     -> PinkLightScheme
        }
        "静谧蓝" -> when {
            darkTheme && amoledBlack -> BlueAmoledScheme
            darkTheme                -> BlueDarkScheme
            else                     -> BlueLightScheme
        }
        else -> /* 经典绿 (默认) */ when {
            darkTheme && amoledBlack -> GreenAmoledScheme
            darkTheme                -> GreenDarkScheme
            else                     -> GreenLightScheme
        }
    }
}

@Composable
fun EhTheme(
    darkTheme: Boolean = false,
    amoledBlack: Boolean = false,
    themeColorName: String = "经典绿",
    content: @Composable () -> Unit
) {
    val view = LocalContext.current as? android.app.Activity
    if (view != null) {
        androidx.compose.runtime.SideEffect {
            val window = view.window
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowCompat.getInsetsController(
                window, view.window.decorView
            ).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val colorScheme = resolveColorScheme(themeColorName, darkTheme, amoledBlack)

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
