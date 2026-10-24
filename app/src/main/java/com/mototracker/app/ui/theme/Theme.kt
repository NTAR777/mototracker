package com.mototracker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Всегда тёмная тема — так читать приборы на солнце и в темноте проще,
// и это «приборный» стиль для мотоциклетного приложения.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB300),          // янтарный — как приборная подсветка
    onPrimary = Color(0xFF1A1A1A),
    secondary = Color(0xFF4FC3F7),
    onSecondary = Color(0xFF0E1116),
    error = Color(0xFFEF5350),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFFB8C0CC)
)

@Composable
fun MotoTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
