package com.frizovdanya.pixpet

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val GUPetLight = lightColorScheme(
    primary = Color(0xFF93543E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF62603D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E5B9),
    onTertiaryContainer = Color(0xFF1E1D05),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF241A16),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF241A16),
    surfaceVariant = Color(0xFFF4DED5),
    onSurfaceVariant = Color(0xFF53433D),
    outline = Color(0xFF85736B),
    outlineVariant = Color(0xFFD8C2B9),
)

private val GUPetDark = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5A1D08),
    primaryContainer = Color(0xFF78351F),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFE6BFAE),
    onSecondary = Color(0xFF442A1F),
    secondaryContainer = Color(0xFF5D4034),
    onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFCDCA9F),
    onTertiary = Color(0xFF333219),
    tertiaryContainer = Color(0xFF49482D),
    onTertiaryContainer = Color(0xFFE9E5B9),
    background = Color(0xFF181210),
    onBackground = Color(0xFFF1DFD8),
    surface = Color(0xFF181210),
    onSurface = Color(0xFFF1DFD8),
    surfaceVariant = Color(0xFF53433D),
    onSurfaceVariant = Color(0xFFD8C2B9),
    outline = Color(0xFFA08D84),
    outlineVariant = Color(0xFF53433D),
)

@Composable
fun GUPetTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) GUPetDark else GUPetLight
    MaterialTheme(colorScheme = scheme, content = content)
}
