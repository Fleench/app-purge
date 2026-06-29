package com.apppurge.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFB91C1C),
    onPrimary = Color.White,
    secondary = Color(0xFF334155),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE5E7EB),
)

@Composable
fun AppPurgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
