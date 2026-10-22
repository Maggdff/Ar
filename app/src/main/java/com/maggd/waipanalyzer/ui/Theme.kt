package com.maggd.waipanalyzer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF00796B)
private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF455A64), background = Color(0xFFF6F8F8), surface = Color.White
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4DB6AC), onPrimary = Color(0xFF00201C), primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB0BEC5), background = Color(0xFF101414), surface = Color(0xFF181D1D)
)

@Composable
fun WaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
