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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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

    // Tracks a fired long-press so the key-up that follows it is swallowed (otherwise
    // the normal click would ALSO fire, opening the card right after removing it).
    var longPressFired by remember { mutableStateOf(false) }

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
    val elevation by animateFloatAsState(
        targetValue = if (focused) 20f else 0f,
        label = "elevation"
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
                // event, so it never triggered. We detect the hold ourselves off the key
                // event: Android auto-repeats a held DPAD_CENTER/Enter, so once the key has
                // been down ~450ms (or the framework flags it a long-press) we fire
                // onLongClick and consume the release so the tap-to-open doesn't also run.
                .then(
                    if (onLongClick != null)
                        Modifier.onPreviewKeyEvent { ke ->
                            if (ke.key != Key.DirectionCenter && ke.key != Key.Enter && ke.key != Key.NumPadEnter)
                                return@onPreviewKeyEvent false
                            val native = ke.nativeKeyEvent
                            when (native.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    val held = native.eventTime - native.downTime
                                    if (!longPressFired && (native.isLongPress || held >= 450L)) {
                                        longPressFired = true
                                        onLongClick()
                                        true
                                    } else false
                                }
                                android.view.KeyEvent.ACTION_UP -> {
                                    if (longPressFired) { longPressFired = false; true } else false
                                }
                                else -> false
                            }
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
                    // clip = false lets the shadow bleed past the poster edges so the
                    // focused card visibly floats above its neighbours.
                    .shadow(elevation.dp, RoundedCornerShape(10.dp), clip = false)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (focused) 3.dp else 0.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
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
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}

// A bright, high-contrast focus ring. On a 10-foot TV UI the Material default focus
// overlay is nearly invisible on already-filled green buttons; a white ring reads
// clearly against both the green fill and the dark background. Used by every button
// so "which control am I on" is unambiguous everywhere.
fun Modifier.tvFocusRing(focused: Boolean, shape: Shape = CircleShape): Modifier =
    this.border(
        width = if (focused) 3.dp else 0.dp,
        color = if (focused) Color.White else Color.Transparent,
        shape = shape
    )

// Filled button with a clear TV focus state (white ring + slight lift). Drop-in for
// Material3 Button across the app.
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // No graphicsLayer scale here: a scale on the focusable node distorts the focus rect
    // that D-pad search reads and can trap vertical navigation. The white ring is the cue.
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.tvFocusRing(focused),
        content = content
    )
}

// Outlined variant with the same focus ring so it's obvious even against its own outline.
@Composable
fun TvOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.tvFocusRing(focused),
        content = content
    )
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
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
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
