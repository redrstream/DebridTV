package io.debridtv.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.debridtv.app.data.cinemeta.Meta
import io.debridtv.app.ui.theme.Accent
import io.debridtv.app.ui.theme.accentGlow
import io.debridtv.app.ui.theme.primaryButtonBrush

data class CardItem(val id: String, val type: String, val title: String, val poster: String?)

fun Meta.toCard(fallbackType: String): CardItem =
    CardItem(id = id, type = type ?: fallbackType, title = name, poster = poster)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: CardItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    onLongClick: (() -> Unit)? = null,
    // When another card in the same row is focused, non-focused cards dim so the
    // selection pops. Driven by MediaRow.
    dimmed: Boolean = false,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChanged?.invoke(focused) }

    val cardShape = RoundedCornerShape(12.dp)

    // A modest, non-bouncy lift. dampingRatio 0.55 (underdamped) used to overshoot and
    // wobble; combined with center-origin scaling that made every card bob up AND down as
    // focus swept the row — dizzying on fast scroll. NoBouncy settles cleanly, and the
    // bottom transformOrigin (below) means the card only rises upward, baseline fixed.
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (dimmed && !focused) 0.5f else 1f,
        label = "alpha"
    )

    Column(
        modifier = modifier
            .width(140.dp)
            // Dimming (alpha) only — an identity transform otherwise. IMPORTANT: the focus
            // lift is scaled on the INNER visual box below, never here. A graphicsLayer
            // scale on this Column (an ancestor of the focusable poster) distorts the focus
            // rectangle that D-pad search reads, which breaks DOWN navigation — focus gets
            // trapped in the row, bouncing between sibling cards instead of dropping to the
            // next row.
            .graphicsLayer { this.alpha = alpha }
    ) {
        Box(
            // The focusable/clickable node. Kept at its natural 200.dp size (no transform)
            // so its focus rect is undistorted; only the inner box scales.
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                // Long-press to remove. Compose's combinedClickable(onLongClick=…) only
                // fires from a TOUCH long-press — on a TV remote the D-pad center is a key
                // event, so it never triggered. We detect the hold ourselves: on the key
                // RELEASE, if the center/Enter key was held past the threshold, fire
                // onLongClick and consume the event so the normal tap-to-open doesn't run.
                // Firing on release (not mid-hold) is deliberate — the Remove dialog opens
                // only once the button is up, so the release can't land on the just-opened
                // dialog and dismiss it (which is exactly what happened when we fired on the
                // ACTION_DOWN: the dialog appeared, then the key-up closed it instantly).
                .then(
                    if (onLongClick != null)
                        Modifier.onPreviewKeyEvent { ke ->
                            if (ke.key != Key.DirectionCenter && ke.key != Key.Enter && ke.key != Key.NumPadEnter)
                                return@onPreviewKeyEvent false
                            val native = ke.nativeKeyEvent
                            if (native.action == android.view.KeyEvent.ACTION_UP) {
                                val held = native.eventTime - native.downTime
                                if (held >= 450L) { onLongClick(); true } else false
                            } else false
                        }
                    else Modifier
                )
                .clickable(interactionSource = interaction, indication = null) { onClick() }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // The focus lift: scales the poster visual only. Grows upward from a
                    // fixed bottom baseline so the label + row baseline never move. Being a
                    // child of the focusable Box, this transform does NOT affect focus search.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    // A brand-coloured glow blooms behind the focused card so it lifts off
                    // the row (the premium tell). clip=false in accentGlow lets it bleed past
                    // the edges. Non-focused cards get a faint hairline for a "framed" look.
                    .accentGlow(focused, cardShape, elevation = 24)
                    .clip(cardShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (focused) 2.5.dp else 1.dp,
                        color = if (focused) Accent else Color.White.copy(alpha = 0.08f),
                        shape = cardShape
                    )
            ) {
                if (!item.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
        )
    }
}

// A bright, high-contrast focus ring. On a 10-foot TV UI the Material default focus
// overlay is nearly invisible on already-filled green buttons; a white ring reads
// clearly against both the green fill and the dark background. Used by the custom
// chips (Play/Queue/Resume) so "which control am I on" is unambiguous.
fun Modifier.tvFocusRing(focused: Boolean, shape: Shape = CircleShape): Modifier =
    this.border(
        width = if (focused) 3.dp else 0.dp,
        color = if (focused) Color.White else Color.Transparent,
        shape = shape
    )

// A pill button with a gradient fill + brand glow on focus — the "cooler" button look
// from the reference designs. Drop-in for the old Material3 Button across the app.
// Built as a plain clickable Row (not a Material Button) so it can carry a real gradient
// and a coloured glow; the Row is the focusable node and never scales, so it stays clear
// of the D-pad focus-rect trap. Content colour + a button-sized text style are supplied
// so existing `Text("…")` / `Icon(…)` call sites render as before.
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = CircleShape
    Row(
        modifier = modifier
            .accentGlow(enabled && focused, shape, elevation = 14)
            .clip(shape)
            .background(primaryButtonBrush(focused))
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = shape
            )
            .graphicsLayer { this.alpha = if (enabled) 1f else 0.5f }
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onPrimary,
            LocalTextStyle provides MaterialTheme.typography.labelLarge
        ) { content() }
    }
}

// Outlined variant: an accent-bordered pill that fills in (with the same gradient +
// glow) when focused, so it's obvious on a 10-foot screen which control is active.
@Composable
fun TvOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = CircleShape
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .accentGlow(enabled && focused, shape, elevation = 14)
            .clip(shape)
            .then(if (focused) Modifier.background(primaryButtonBrush(true)) else Modifier)
            .border(
                width = 1.5.dp,
                color = if (focused) Color.White else MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                shape = shape
            )
            .graphicsLayer { this.alpha = if (enabled) 1f else 0.5f }
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides fg,
            LocalTextStyle provides MaterialTheme.typography.labelLarge
        ) { content() }
    }
}

// A quiet "ghost" pill for navigation (Search / Library / Settings / Back). It reads as
// a subtle dark chip until focused, then lights up with the same green gradient + glow —
// so the top bar isn't a wall of green and the control you're on is unmistakable. Filled
// green pills (TvButton) are reserved for real actions like Play / Save.
@Composable
fun TvGhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = CircleShape
    Row(
        modifier = modifier
            .accentGlow(focused, shape, elevation = 14)
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant else Color.White.copy(alpha = 0.05f)
            )
            .then(if (focused) Modifier.background(primaryButtonBrush(true)) else Modifier)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.10f),
                shape = shape
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
            LocalTextStyle provides MaterialTheme.typography.labelLarge
        ) { content() }
    }
}

// A section title with a short accent bar beside it — the small "designed" cue used
// before every row in the reference designs. Reused by every screen's row/section head.
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            Modifier
                .height(20.dp)
                .width(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun MediaRow(
    title: String,
    items: List<CardItem>,
    progressFor: (CardItem) -> Float = { 0f },
    onLongClick: ((CardItem) -> Unit)? = null,
    onClick: (CardItem) -> Unit
) {
    if (items.isEmpty()) return
    // Track which card holds focus so its siblings can dim. Null = row not focused,
    // so every card stays at full brightness.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val rowFocused = focusedKey != null

    // focusGroup() on the row's outer container (the direct child of the LazyColumn
    // item) is what lets D-pad DOWN/UP move BETWEEN rows. Without it, focus search
    // stays inside the current LazyRow and bounces left/right between sibling cards
    // instead of dropping to the row below.
    Column(Modifier.padding(vertical = 10.dp).focusGroup()) {
        SectionHeader(
            title = title,
            // Extra bottom gap gives the focused card room to rise upward without
            // overlapping this title.
            modifier = Modifier.padding(start = 24.dp, bottom = 16.dp)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp)) {
            items(items, key = { it.type + it.id }) { item ->
                val key = item.type + item.id
                PosterCard(
                    item = item,
                    onClick = { onClick(item) },
                    progress = progressFor(item),
                    modifier = Modifier.padding(end = 14.dp),
                    onLongClick = onLongClick?.let { cb -> { cb(item) } },
                    dimmed = rowFocused,
                    onFocusChanged = { f ->
                        if (f) focusedKey = key
                        else if (focusedKey == key) focusedKey = null
                    }
                )
            }
        }
    }
}
