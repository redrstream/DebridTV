package io.debridtv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF04150D),
    secondary = Color(0xFF7FB0FF),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE7EAEE),
    surface = Color(0xFF161A1F),
    onSurface = Color(0xFFE7EAEE),
    surfaceVariant = Color(0xFF222831),
    onSurfaceVariant = Color(0xFFB6BEC8),
    error = Color(0xFFFF6B6B)
)

@Composable
fun DebridTvTheme(content: @Composable () -> Unit) {
    // Always dark — this is a 10-foot TV UI.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = Colors, content = content)
}
