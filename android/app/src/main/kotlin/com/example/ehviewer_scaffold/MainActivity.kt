package com.example.ehviewer_scaffold

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.example.ehviewer_scaffold.ui.screens.MainAppNavHost
import com.example.ehviewer_scaffold.ui.settings.AppSettings
import com.example.ehviewer_scaffold.ui.theme.EhTheme

class MainActivity : FragmentActivity() {

    // 响应式主题状态：外观设置保存后可随时刷新
    var themeColorName by mutableStateOf("经典绿")
    var amoledBlack    by mutableStateOf(false)
    var followSystem   by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure system bars draw transparently and window draws system bar backgrounds
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = lp // Must reassign to invoke dispatchWindowAttributesChanged
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        // 若已开启“在最近任务中隐藏截图”，启动时立即应用 FLAG_SECURE
        if (AppSettings.isBlurInRecentTasks(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // 初始读取外观设置
        refreshThemeSettings()

        // 若已开启生物识别锁，启动时弹出验证
        checkBiometricLock()

        setContent {
            // 由于 themeColorName / amoledBlack / followSystem 是 mutableStateOf，
            // 任何地方调用 refreshThemeSettings() 后 Compose 会自动重组
            val systemDark = isSystemInDarkTheme()
            val darkTheme = if (followSystem) systemDark else amoledBlack

            // 像素偏移防烧屏：AMOLED + 像素偏移开启时，每 60 秒平移 ±2dp
            val pixelShiftEnabled = amoledBlack && AppSettings.isPixelShift(this@MainActivity)
            val shiftX = remember { Animatable(0f) }
            val shiftY = remember { Animatable(0f) }
            LaunchedEffect(pixelShiftEnabled) {
                if (!pixelShiftEnabled) {
                    shiftX.snapTo(0f)
                    shiftY.snapTo(0f)
                    return@LaunchedEffect
                }
                val offsets = listOf(0f to 0f, 2f to 1f, -1f to 2f, -2f to -1f, 1f to -2f)
                val pixelSpring = spring<Float>(
                    stiffness = Spring.StiffnessLow,
                    dampingRatio = Spring.DampingRatioNoBouncy
                )
                var idx = 0
                while (true) {
                    kotlinx.coroutines.delay(60_000L)
                    idx = (idx + 1) % offsets.size
                    val (tx, ty) = offsets[idx]
                    shiftX.animateTo(tx, animationSpec = pixelSpring)
                    shiftY.animateTo(ty, animationSpec = pixelSpring)
                }
            }

            EhTheme(
                darkTheme     = darkTheme,
                amoledBlack   = amoledBlack,
                themeColorName = themeColorName
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = shiftX.value.dp.toPx()
                            translationY = shiftY.value.dp.toPx()
                        },
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppNavHost()
                }
            }
        }
    }

    /** 从 SharedPreferences 刷新主题相关状态，触发 Compose 重组 */
    fun refreshThemeSettings() {
        themeColorName = AppSettings.getThemeColor(this)
        amoledBlack    = AppSettings.isAmoledBlack(this)
        followSystem   = AppSettings.isThemeFollowSystem(this)
    }

    /** 若用户开启了生物识别锁，则弹出 BiometricPrompt 拦截；成功才放行 */
    private fun checkBiometricLock() {
        if (!AppSettings.isBiometricUnlock(this)) return
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // 验证通过，什么都不需要做
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    // 用户取消 → 退出 App
                    finish()
                }
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁 Eh-ru")
            .setSubtitle("使用生物识别或屏幕锁定继续")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台刷新主题（用户可能从设置返回）
        refreshThemeSettings()

        if (AppSettings.isBlurInRecentTasks(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
