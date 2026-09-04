package io.debridtv.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// The brand accent, plus a deeper shade for gradient fills. Kept here (not just in the
// Material scheme) so glows and gradients can reference the exact tones.
val Accent = Color(0xFF3DDC97)
val AccentBright = Color(0xFF5CF0B0)
val AccentDeep = Color(0xFF1FA774)

/**
 * A soft, brand-coloured glow around a shape — the premium "lift" that focus gets in
 * the reference designs. It's a tinted elevation shadow (spot/ambient colour), NOT a
 * blur pass, so it's cheap. Tinting needs API 28+ (our target TV is API 30+); on older
 * devices it degrades to a faint neutral shadow. Elevation doesn't change layout size,
 * so this is safe on a focusable node — no D-pad focus-rect distortion.
 */
fun Modifier.accentGlow(
    active: Boolean,
    shape: Shape,
    color: Color = Accent,
    elevation: Int = 16
): Modifier =
    if (!active) this
    else this.shadow(
        elevation = elevation.dp,
        shape = shape,
        clip = false,
        ambientColor = color,
        spotColor = color
    )

/** Gradient fill for a primary (filled) pill button — brighter while focused. */
@Composable
fun primaryButtonBrush(focused: Boolean): Brush = Brush.verticalGradient(
    if (focused) listOf(AccentBright, Accent) else listOf(Accent, AccentDeep)
)

/**
 * A hairline top-lit border that gives flat panels a subtle "glass" edge (like the
 * cards in the reference designs). Brighter/accented when [focused].
 */
fun Modifier.glassBorder(shape: Shape, focused: Boolean = false, accent: Color = Accent): Modifier =
    this.border(
        width = if (focused) 1.5.dp else 1.dp,
        color = if (focused) accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f),
        shape = shape
    )
