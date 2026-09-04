package io.debridtv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// The flat window background colour. The app paints a subtle gradient on top of this
// (AppGradient), so this is the tone the gradient starts from at the very top and the
// colour every scrim fades into — keep the three in sync.
val BackgroundTop = Color(0xFF0E1013)
private val BackgroundBottom = Color(0xFF07090C)

private val Colors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF04150D),
    secondary = Color(0xFF7FB0FF),
    background = BackgroundTop,
    onBackground = Color(0xFFE7EAEE),
    surface = Color(0xFF161A1F),
    onSurface = Color(0xFFE7EAEE),
    surfaceVariant = Color(0xFF222831),
    onSurfaceVariant = Color(0xFFB6BEC8),
    error = Color(0xFFFF6B6B)
)

/**
 * A gentle top-to-bottom darkening applied behind the whole app. It reads as
 * "designed" rather than a flat fill, but is a single vertical gradient — no cost.
 * The top tone equals the theme background so a screen that fades its own scrim to
 * [BackgroundTop] blends seamlessly into it.
 */
fun appGradient(): Brush = Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom))

@Composable
fun DebridTvTheme(content: @Composable () -> Unit) {
    // Always dark — this is a 10-foot TV UI.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = Colors, content = content)
}
