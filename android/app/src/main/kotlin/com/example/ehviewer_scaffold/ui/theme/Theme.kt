package com.example.ehviewer_scaffold.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = EhPrimaryDark,
    onPrimary = EhOnPrimaryDark,
    primaryContainer = EhPrimaryContainerDark,
    onPrimaryContainer = EhOnPrimaryContainerDark,
    secondary = EhSecondaryDark,
    onSecondary = EhOnSecondaryDark,
    secondaryContainer = EhSecondaryContainerDark,
    onSecondaryContainer = EhOnSecondaryContainerDark,
    background = AmoledBackground,
    surface = AmoledSurface
)

private val LightColorScheme = lightColorScheme(
    primary = EhPrimaryLight,
    onPrimary = EhOnPrimaryLight,
    primaryContainer = EhPrimaryContainerLight,
    onPrimaryContainer = EhOnPrimaryContainerLight,
    secondary = EhSecondaryLight,
    onSecondary = EhOnSecondaryLight,
    secondaryContainer = EhSecondaryContainerLight,
    onSecondaryContainer = EhOnSecondaryContainerLight
)

@Composable
fun EhTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
