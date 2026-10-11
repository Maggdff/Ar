package com.maggd.arabicskills.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// «حبر ومداد»: حبر نيلي على ورق رمادي بارد، وقلم مراجعة أحمر للتنبيه، وتظليل أصفر للتحديد.
val Ink = Color(0xFF22305A)
val InkSoft = Color(0xFF4A5678)
val Paper = Color(0xFFF3F4F1)
val PaperRaised = Color(0xFFFBFBF9)
val Marker = Color(0xFFB02A37)
val Highlight = Color(0xFFF5E49A)
val Ok = Color(0xFF2E7D32)
val OkBg = Color(0xFFE3F1E4)
val Bad = Color(0xFFB02A37)
val BadBg = Color(0xFFF9E4E4)

private val Light = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2F3), onPrimaryContainer = Color(0xFF141D3A),
    secondary = Marker, onSecondary = Color.White,
    secondaryContainer = Highlight, onSecondaryContainer = Color(0xFF3A3000),
    background = Paper, onBackground = Color(0xFF1A1C22),
    surface = PaperRaised, onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE7E9EE), onSurfaceVariant = InkSoft,
    outline = Color(0xFF8A90A3), outlineVariant = Color(0xFFC9CDD8),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C0F2), onPrimary = Color(0xFF111A38),
    primaryContainer = Color(0xFF2B3866), onPrimaryContainer = Color(0xFFDDE2F3),
    secondary = Color(0xFFF08A93), onSecondary = Color(0xFF3B0A10),
    secondaryContainer = Color(0xFF51471A), onSecondaryContainer = Highlight,
    background = Color(0xFF12151D), onBackground = Color(0xFFE4E6EC),
    surface = Color(0xFF181C26), onSurface = Color(0xFFE4E6EC),
    surfaceVariant = Color(0xFF242938), onSurfaceVariant = Color(0xFFA9B0C6),
    outline = Color(0xFF6F768C), outlineVariant = Color(0xFF3A4054),
)

/** العناوين بخط النسخ (Serif في أندرويد يُعرض نسخًا للعربية) والمتن بالخط الافتراضي المقروء. */
private val Heading = FontFamily.Serif
private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Heading, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 42.sp),
    titleLarge = TextStyle(fontFamily = Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = Heading, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 28.sp),
)

// أنصاف أقطار متدرجة حسب الدور: لا شكل واحد لكل شيء
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        // واجهة عربية: اتجاه من اليمين إلى اليسار دائمًا
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
