package com.example.ehviewer_scaffold.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── AMOLED Pure Black ─────────────────────────────────────────────────────
val AmoledBackground = Color(0xFF000000)
val AmoledSurface    = Color(0xFF0A0A0A)
val AmoledCard       = Color(0xFF121212)

// ─── 主题 1：经典绿 (Eh Brand Forest Green) ────────────────────────────────
val EhPrimaryLight              = Color(0xFF2E6B4F)
val EhOnPrimaryLight            = Color(0xFFFFFFFF)
val EhPrimaryContainerLight     = Color(0xFFB4F2CE)
val EhOnPrimaryContainerLight   = Color(0xFF002113)

val EhPrimaryDark               = Color(0xFF99D5B3)
val EhOnPrimaryDark             = Color(0xFF003822)
val EhPrimaryContainerDark      = Color(0xFF135138)
val EhOnPrimaryContainerDark    = Color(0xFFB4F2CE)

val EhSecondaryLight            = Color(0xFF4F6354)
val EhOnSecondaryLight          = Color(0xFFFFFFFF)
val EhSecondaryContainerLight   = Color(0xFFD2E8D5)
val EhOnSecondaryContainerLight = Color(0xFF0C1F13)

val EhSecondaryDark             = Color(0xFFB6CCBA)
val EhOnSecondaryDark           = Color(0xFF213527)
val EhSecondaryContainerDark    = Color(0xFF374B3D)
val EhOnSecondaryContainerDark  = Color(0xFFD2E8D5)

val GreenDarkScheme = darkColorScheme(
    primary                = EhPrimaryDark,
    onPrimary              = EhOnPrimaryDark,
    primaryContainer       = EhPrimaryContainerDark,
    onPrimaryContainer     = EhOnPrimaryContainerDark,
    secondary              = EhSecondaryDark,
    onSecondary            = EhOnSecondaryDark,
    secondaryContainer     = EhSecondaryContainerDark,
    onSecondaryContainer   = EhOnSecondaryContainerDark,
    background             = Color(0xFF101510),
    surface                = Color(0xFF161C16),
    surfaceVariant         = Color(0xFF1E2B1E),
)

val GreenAmoledScheme = darkColorScheme(
    primary                = EhPrimaryDark,
    onPrimary              = EhOnPrimaryDark,
    primaryContainer       = EhPrimaryContainerDark,
    onPrimaryContainer     = EhOnPrimaryContainerDark,
    secondary              = EhSecondaryDark,
    onSecondary            = EhOnSecondaryDark,
    secondaryContainer     = EhSecondaryContainerDark,
    onSecondaryContainer   = EhOnSecondaryContainerDark,
    background             = AmoledBackground,
    surface                = AmoledSurface,
    surfaceVariant         = Color(0xFF0D0D0D),
)

// ─── Light-scheme surfaces ────────────────────────────────────────────────
//
// The three light schemes only ever set the brand colours, so every other role
// fell back to Material3's defaults. That is fine for buttons and text, but
// `surfaceVariant` is used as the *thumbnail placeholder* behind every cover in
// the waterfall, and the generated default is a lavender grey that reads as a
// rendering bug against these green/pink/blue palettes.
//
// Defining it explicitly keeps the empty cover boxes neutral and on-theme.
val EhLightSurface        = Color(0xFFFFFFFF)
val EhLightSurfaceVariant = Color(0xFFE9E7EC)

val GreenLightScheme = lightColorScheme(
    primary                = EhPrimaryLight,
    onPrimary              = EhOnPrimaryLight,
    primaryContainer       = EhPrimaryContainerLight,
    onPrimaryContainer     = EhOnPrimaryContainerLight,
    secondary              = EhSecondaryLight,
    onSecondary            = EhOnSecondaryLight,
    secondaryContainer     = EhSecondaryContainerLight,
    onSecondaryContainer   = EhOnSecondaryContainerLight,
    surface                = EhLightSurface,
    surfaceVariant         = EhLightSurfaceVariant,
)

// ─── 主题 2：樱花粉 (Sakura Pink) ──────────────────────────────────────────
val PinkPrimaryLight              = Color(0xFFC2185B)
val PinkOnPrimaryLight            = Color(0xFFFFFFFF)
val PinkPrimaryContainerLight     = Color(0xFFFFD6E4)
val PinkOnPrimaryContainerLight   = Color(0xFF3E001E)

val PinkPrimaryDark               = Color(0xFFFFAFCB)
val PinkOnPrimaryDark             = Color(0xFF650033)
val PinkPrimaryContainerDark      = Color(0xFF8F004B)
val PinkOnPrimaryContainerDark    = Color(0xFFFFD6E4)

val PinkSecondaryLight            = Color(0xFF74565E)
val PinkOnSecondaryLight          = Color(0xFFFFFFFF)
val PinkSecondaryContainerLight   = Color(0xFFFFD8DF)
val PinkOnSecondaryContainerLight = Color(0xFF2C151C)

val PinkSecondaryDark             = Color(0xFFE3BDC4)
val PinkOnSecondaryDark           = Color(0xFF432930)
val PinkSecondaryContainerDark    = Color(0xFF5B3F46)
val PinkOnSecondaryContainerDark  = Color(0xFFFFD8DF)

val PinkDarkScheme = darkColorScheme(
    primary                = PinkPrimaryDark,
    onPrimary              = PinkOnPrimaryDark,
    primaryContainer       = PinkPrimaryContainerDark,
    onPrimaryContainer     = PinkOnPrimaryContainerDark,
    secondary              = PinkSecondaryDark,
    onSecondary            = PinkOnSecondaryDark,
    secondaryContainer     = PinkSecondaryContainerDark,
    onSecondaryContainer   = PinkOnSecondaryContainerDark,
    background             = Color(0xFF1A1014),
    surface                = Color(0xFF201419),
    surfaceVariant         = Color(0xFF2A1B1F),
)

val PinkAmoledScheme = darkColorScheme(
    primary                = PinkPrimaryDark,
    onPrimary              = PinkOnPrimaryDark,
    primaryContainer       = PinkPrimaryContainerDark,
    onPrimaryContainer     = PinkOnPrimaryContainerDark,
    secondary              = PinkSecondaryDark,
    onSecondary            = PinkOnSecondaryDark,
    secondaryContainer     = PinkSecondaryContainerDark,
    onSecondaryContainer   = PinkOnSecondaryContainerDark,
    background             = AmoledBackground,
    surface                = AmoledSurface,
    surfaceVariant         = Color(0xFF0D0D0D),
)

val PinkLightScheme = lightColorScheme(
    primary                = PinkPrimaryLight,
    onPrimary              = PinkOnPrimaryLight,
    primaryContainer       = PinkPrimaryContainerLight,
    onPrimaryContainer     = PinkOnPrimaryContainerLight,
    secondary              = PinkSecondaryLight,
    onSecondary            = PinkOnSecondaryLight,
    secondaryContainer     = PinkSecondaryContainerLight,
    onSecondaryContainer   = PinkOnSecondaryContainerLight,
    surface                = EhLightSurface,
    surfaceVariant         = EhLightSurfaceVariant,
)

// ─── 主题 3：静谧蓝 (Midnight Blue) ────────────────────────────────────────
val BluePrimaryLight              = Color(0xFF1565C0)
val BlueOnPrimaryLight            = Color(0xFFFFFFFF)
val BluePrimaryContainerLight     = Color(0xFFD5E3FF)
val BlueOnPrimaryContainerLight   = Color(0xFF001945)

val BluePrimaryDark               = Color(0xFFAAC7FF)
val BlueOnPrimaryDark             = Color(0xFF003271)
val BluePrimaryContainerDark      = Color(0xFF004A9F)
val BlueOnPrimaryContainerDark    = Color(0xFFD5E3FF)

val BlueSecondaryLight            = Color(0xFF545F70)
val BlueOnSecondaryLight          = Color(0xFFFFFFFF)
val BlueSecondaryContainerLight   = Color(0xFFD8E3F7)
val BlueOnSecondaryContainerLight = Color(0xFF111C2B)

val BlueSecondaryDark             = Color(0xFFBCC7DB)
val BlueOnSecondaryDark           = Color(0xFF263140)
val BlueSecondaryContainerDark    = Color(0xFF3C4858)
val BlueOnSecondaryContainerDark  = Color(0xFFD8E3F7)

val BlueDarkScheme = darkColorScheme(
    primary                = BluePrimaryDark,
    onPrimary              = BlueOnPrimaryDark,
    primaryContainer       = BluePrimaryContainerDark,
    onPrimaryContainer     = BlueOnPrimaryContainerDark,
    secondary              = BlueSecondaryDark,
    onSecondary            = BlueOnSecondaryDark,
    secondaryContainer     = BlueSecondaryContainerDark,
    onSecondaryContainer   = BlueOnSecondaryContainerDark,
    background             = Color(0xFF0E1220),
    surface                = Color(0xFF121828),
    surfaceVariant         = Color(0xFF1A2235),
)

val BlueAmoledScheme = darkColorScheme(
    primary                = BluePrimaryDark,
    onPrimary              = BlueOnPrimaryDark,
    primaryContainer       = BluePrimaryContainerDark,
    onPrimaryContainer     = BlueOnPrimaryContainerDark,
    secondary              = BlueSecondaryDark,
    onSecondary            = BlueOnSecondaryDark,
    secondaryContainer     = BlueSecondaryContainerDark,
    onSecondaryContainer   = BlueOnSecondaryContainerDark,
    background             = AmoledBackground,
    surface                = AmoledSurface,
    surfaceVariant         = Color(0xFF0D0D0D),
)

val BlueLightScheme = lightColorScheme(
    primary                = BluePrimaryLight,
    onPrimary              = BlueOnPrimaryLight,
    primaryContainer       = BluePrimaryContainerLight,
    onPrimaryContainer     = BlueOnPrimaryContainerLight,
    secondary              = BlueSecondaryLight,
    onSecondary            = BlueOnSecondaryLight,
    secondaryContainer     = BlueSecondaryContainerLight,
    onSecondaryContainer   = BlueOnSecondaryContainerLight,
    surface                = EhLightSurface,
    surfaceVariant         = EhLightSurfaceVariant,
)
