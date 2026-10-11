package com.maggd.arabicskills.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

val Green = Color(0xFF0F4C45)
val GreenLight = Color(0xFF1E7A6E)
val Gold = Color(0xFFC9A227)
val Cream = Color(0xFFFBF8F1)
val Ok = Color(0xFF2E7D32)
val OkBg = Color(0xFFE6F4E7)
val Bad = Color(0xFFC62828)
val BadBg = Color(0xFFFCE8E6)

private val Light = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EAE5), onPrimaryContainer = Green,
    secondary = Gold, onSecondary = Color.Black,
    secondaryContainer = Color(0xFFF6EBC4), onSecondaryContainer = Color(0xFF3B2F00),
    background = Cream, surface = Color.White, surfaceVariant = Color(0xFFEFEAE0),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7CCBBE), onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F4C45), onPrimaryContainer = Color(0xFFD5EAE5),
    secondary = Gold, onSecondary = Color.Black,
    secondaryContainer = Color(0xFF4A3D00), onSecondaryContainer = Color(0xFFF6EBC4),
    background = Color(0xFF101514), surface = Color(0xFF171D1C), surfaceVariant = Color(0xFF232B2A),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        // واجهة عربية: اتجاه من اليمين إلى اليسار دائمًا
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
