package net.guteam.gupet

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Акцент по умолчанию — из :root в index.html (--accent: #ff4d30).
val GUPetAccentDefault = Color(0xFFFF4D30)

// Набор дизайн-токенов GUStay-стиля.
data class GUPetTokens(
    val bg: Color,
    val fg: Color,
    val title: Color,
    val fgMuted: Color,
    val fgFaint: Color,
    val card: Color,
    val cardHover: Color,
    val borderSoft: Color,
    val borderMid: Color,
    val segmentBg: Color,
    val segmentFg: Color,
    val accent: Color,
    val onAccent: Color,
    val panel: Color,
)

// Тёмная тема (значения по умолчанию из :root index.html).
fun gupetDarkTokens(accent: Color): GUPetTokens = GUPetTokens(
    bg = Color(0xFF0B0B0B),
    fg = Color(0xFFF0F0F0),
    title = Color(0xFFFFFFFF),
    fgMuted = Color(0x99FFFFFF),
    fgFaint = Color(0x59FFFFFF),
    card = Color(0x0AFFFFFF),
    cardHover = Color(0x12FFFFFF),
    borderSoft = Color(0x14FFFFFF),
    borderMid = Color(0x33FFFFFF),
    segmentBg = Color(0x0DFFFFFF),
    segmentFg = Color(0x99FFFFFF),
    accent = accent,
    onAccent = Color(0xFFFFFFFF),
    panel = Color(0xFF121212),
)

// Светлая тема (body.light в index.html).
fun gupetLightTokens(accent: Color): GUPetTokens = GUPetTokens(
    bg = Color(0xFFFFFFFF),
    fg = Color(0xFF222222),
    title = Color(0xFF000000),
    fgMuted = Color(0x99000000),
    fgFaint = Color(0x66000000),
    card = Color(0x0A000000),
    cardHover = Color(0x12000000),
    borderSoft = Color(0x1A000000),
    borderMid = Color(0x33000000),
    segmentBg = Color(0x0D000000),
    segmentFg = Color(0x8C000000),
    accent = accent,
    onAccent = Color(0xFFFFFFFF),
    panel = Color(0xFFF6F5F4),
)

val LocalGUPetTokens = staticCompositionLocalOf { gupetDarkTokens(GUPetAccentDefault) }

// Доступ к токенам из любого composable.
val gupetTokens: GUPetTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalGUPetTokens.current

// Типографика: Inter не встроен, поэтому используем системный sans,
// но сохраняем размеры, веса и трекинг из index.html.
object GUPetType {
    val brand = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-3).sp, lineHeight = 72.sp)
    val brandSmall = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, lineHeight = 48.sp)
    val status = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp)
    val toggle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    val hint = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
    val sectionTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    val settingsTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
    val label = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val sublabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp)
    val segment = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val rangeValue = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val devButton = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val devSubtitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val aboutTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
    val aboutSubtitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val aboutText = TextStyle(fontSize = 14.sp, lineHeight = 23.sp)
    val featureName = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val featureDesc = TextStyle(fontSize = 13.sp, lineHeight = 21.sp)
    val confirmTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    val confirmMessage = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    val statValue = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    val statLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

// Размер заголовков секций настраивается пользователем (10..20 sp).
val LocalSectionTitleSize = staticCompositionLocalOf { 13.sp }

@Composable
fun GUPetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color = GUPetAccentDefault,
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) gupetDarkTokens(accent) else gupetLightTokens(accent)
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = accent,
            onPrimary = tokens.onAccent,
            background = tokens.bg,
            onBackground = tokens.fg,
            surface = tokens.bg,
            onSurface = tokens.fg,
            surfaceVariant = tokens.panel,
            onSurfaceVariant = tokens.fgMuted,
            outline = tokens.borderMid,
            outlineVariant = tokens.borderSoft,
            error = Color(0xFFE5484D),
            onError = Color(0xFFFFFFFF),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = tokens.onAccent,
            background = tokens.bg,
            onBackground = tokens.fg,
            surface = tokens.bg,
            onSurface = tokens.fg,
            surfaceVariant = tokens.panel,
            onSurfaceVariant = tokens.fgMuted,
            outline = tokens.borderMid,
            outlineVariant = tokens.borderSoft,
            error = Color(0xFFD93A3F),
            onError = Color(0xFFFFFFFF),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = tokens.bg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = tokens.bg.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalGUPetTokens provides tokens) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(),
            content = content,
        )
    }
}
