package io.debridtv.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import io.debridtv.app.data.alldebrid.MagnetInfo
import io.debridtv.app.di.ServiceLocator
import io.debridtv.app.ui.Routes
import io.debridtv.app.ui.components.CardItem
import io.debridtv.app.ui.components.MediaRow
import io.debridtv.app.ui.components.TvButton
import io.debridtv.app.ui.components.TvOutlinedButton
import io.debridtv.app.ui.components.toCard
import io.debridtv.app.ui.player.PlayerActivity

@Composable
fun HomeScreen(nav: NavHostController) {
    val repo = ServiceLocator.mediaRepo
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val apiKey by ServiceLocator.settings.apiKey.collectAsState(initial = null)
    val preferSurround by ServiceLocator.settings.preferSurround.collectAsState(initial = true)
    val history by ServiceLocator.history.history.collectAsState(initial = emptyList())
    var removeTarget by remember { mutableStateOf<CardItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    var movies by remember { mutableStateOf<List<CardItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<CardItem>>(emptyList()) }
    var rowsState by remember { mutableStateOf(HomeLoad.LOADING) }
    val retryFocus = remember { FocusRequester() }

    // When the error appears, put focus on Retry so it's actionable with one press
    // (the nav bar above doesn't hand focus down to it otherwise).
    LaunchedEffect(rowsState) {
        if (rowsState == HomeLoad.ERROR) {
            delay(100)
            runCatching { retryFocus.requestFocus() }
        }
    }

    // Fetch the Popular rows. repo.popular() swallows network errors into an empty
    // list, so "both empty" is treated as a failure we can surface + retry.
    suspend fun fetchRows() {
        rowsState = HomeLoad.LOADING
        val m = repo.popular("movie").map { it.toCard("movie") }
        val s = repo.popular("series").map { it.toCard("series") }
        movies = m
        series = s
        rowsState = if (m.isEmpty() && s.isEmpty()) HomeLoad.ERROR else HomeLoad.READY
    }

    // Continue Watching entries played from the Library have no IMDb detail page;
    // resolve them by magnet id and jump straight back into playback.
    fun playDebrid(card: CardItem) {
        val magnetId = card.id.substringAfter(":").toLongOrNull() ?: return
        busy = true
        scope.launch {
            try {
                val start = ServiceLocator.history.get(card.id)?.positionMs ?: 0L
                val resolved = ServiceLocator.resolver
                    .resolveReadyMagnet(MagnetInfo(id = magnetId, filename = card.title))
                busy = false
                PlayerActivity.start(
                    context = context,
                    url = resolved.url,
                    title = card.title,
                    key = card.id,
                    type = "debrid",
                    poster = null,
                    startMs = start,
                    preferSurround = preferSurround
                )
            } catch (e: Exception) {
                busy = false
                Toast.makeText(context, e.message ?: "Failed to play", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Cold start: try a few times so a transient network blip doesn't leave the
    // home screen permanently blank. After that the user gets a Retry button.
    LaunchedEffect(Unit) {
        repeat(3) {
            fetchRows()
            if (rowsState == HomeLoad.READY) return@LaunchedEffect
            delay(1500)
        }
    }

    // Build the Continue Watching row.
    //  • Movies / Library (debrid) items map 1:1 from their entry, and only while
    //    genuinely in progress (not finished/marked, >2% watched).
    //  • Series COLLAPSE to one card per show: after you finish an episode the show
    //    should stay here so you can jump to the NEXT episode. We key the card off
    //    the show id and let DetailScreen's resume logic land on the right episode
    //    (it already rolls forward past a finished episode). A show stays here as
    //    long as it has any activity — an in-progress episode to resume, or a
    //    finished one to advance past. (A fully-finished series lingers until you
    //    long-press → Remove; detecting the finale would need a Cinemeta fetch.)
    val cwList = remember(history) {
        val out = mutableListOf<Triple<CardItem, Float, Long>>()
        history.filter { it.type != "series" }
            .filterNot { it.isWatched }
            .filter { it.progress > 0.02f }
            .forEach { e ->
                out += Triple(CardItem(e.key, e.type, e.title, e.poster), e.progress, e.updatedAt)
            }
        history.filter { it.type == "series" }
            .groupBy { it.key.substringBefore(":") }
            .forEach { (showId, entries) ->
                val latest = entries.maxByOrNull { it.updatedAt } ?: return@forEach
                val resumable = !latest.isWatched && latest.progress > 0.02f
                val showTitle = latest.title.substringBefore(" · ").ifBlank { latest.title }
                out += Triple(
                    CardItem(id = showId, type = "series", title = showTitle, poster = latest.poster),
                    if (resumable) latest.progress else 0f,
                    latest.updatedAt
                )
            }
        out.sortedByDescending { it.third }
    }
    val cwCards = cwList.map { it.first }
    val cwProgress = cwList.associate { (it.first.type + it.first.id) to it.second }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        TopBar(nav, current = Routes.HOME)

        if (apiKey.isNullOrBlank()) {
            Text(
                "No AllDebrid API key yet — open Settings to add it before playing.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (cwCards.isNotEmpty()) {
                item {
                    MediaRow(
                        title = "Continue Watching",
                        items = cwCards,
                        progressFor = { card -> cwProgress[card.type + card.id] ?: 0f },
                        onLongClick = { card -> removeTarget = card },
                        onClick = { card ->
                            when (card.type) {
                                // Series cards carry the bare show id; DetailScreen's
                                // resume logic drops us on the next episode to watch.
                                "debrid" -> playDebrid(card)
                                else -> nav.navigate(Routes.detail(card.type, card.id))
                            }
                        }
                    )
                }
            }
            when (rowsState) {
                HomeLoad.LOADING -> item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Loading…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                HomeLoad.ERROR -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Couldn't load — check your connection.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TvButton(
                            onClick = { scope.launch { fetchRows() } },
                            modifier = Modifier
                                .focusRequester(retryFocus)
                                .padding(top = 12.dp)
                        ) { Text("Retry") }
                    }
                }
                HomeLoad.READY -> {
                    item {
                        MediaRow("Popular Movies", movies) { nav.navigate(Routes.detail(it.type, it.id)) }
                    }
                    item {
                        MediaRow("Popular Series", series) { nav.navigate(Routes.detail(it.type, it.id)) }
                    }
                    // Genre rows are lazy: each only hits Cinemeta once it scrolls
                    // into view, so the home screen's first paint stays fast.
                    items(GENRE_ROWS, key = { it.type + it.genre }) { g ->
                        GenreRow(g.title, g.type, g.genre, nav)
                    }
                }
            }
        }

        removeTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text("Remove from Continue Watching?") },
                text = { Text(target.title) },
                confirmButton = {
                    TvButton(onClick = {
                        scope.launch {
                            // Series cards represent a whole show (bare id), so clear
                            // every episode entry; others remove their single entry.
                            if (target.type == "series") ServiceLocator.history.removeShow(target.id)
                            else ServiceLocator.history.remove(target.id)
                        }
                        removeTarget = null
                    }) { Text("Remove") }
                },
                dismissButton = {
                    TvOutlinedButton(onClick = { removeTarget = null }) { Text("Cancel") }
                }
            )
        }
    }

        if (busy) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private enum class HomeLoad { LOADING, READY, ERROR }

@Composable
fun TopBar(nav: NavHostController, current: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (current != Routes.HOME) {
            TvButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    modifier = Modifier.padding(end = 6.dp))
                Text("Back")
            }
        }
        Text(
            "DebridTV",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 24.dp)
        )
        NavButton("Search", Icons.Filled.Search) {
            if (current != Routes.SEARCH) nav.navigate(Routes.SEARCH)
        }
        NavButton("Library", Icons.Filled.VideoLibrary) {
            if (current != Routes.LIBRARY) nav.navigate(Routes.LIBRARY)
        }
        NavButton("Settings", Icons.Filled.Settings) {
            if (current != Routes.SETTINGS) nav.navigate(Routes.SETTINGS)
        }
    }
}

@Composable
private fun NavButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TvButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text(label)
    }
}

// Lazy genre rows shown under the Popular rows on Home. Each fetches from Cinemeta
// only when scrolled into view, and MediaRow renders nothing until data arrives —
// so they add no cost to the home screen's initial load.
private data class GenreRowDef(val title: String, val type: String, val genre: String)

private val GENRE_ROWS = listOf(
    GenreRowDef("Action Movies", "movie", "Action"),
    GenreRowDef("Comedy Movies", "movie", "Comedy"),
    GenreRowDef("Sci-Fi Movies", "movie", "Sci-Fi"),
    GenreRowDef("Drama Series", "series", "Drama"),
    GenreRowDef("Animation", "movie", "Animation")
)

@Composable
private fun GenreRow(title: String, type: String, genre: String, nav: NavHostController) {
    val repo = ServiceLocator.mediaRepo
    // null = still loading. We must NOT render a zero-height row while loading:
    // LazyColumn would recycle it (cancelling the fetch) before it ever gains
    // height, so it could never be scrolled to. Reserve the row's height instead.
    var items by remember(type, genre) { mutableStateOf<List<CardItem>?>(null) }
    LaunchedEffect(type, genre) {
        items = repo.byGenre(type, genre).map { it.toCard(type) }
    }
    when (val loaded = items) {
        null -> {
            // Loading placeholder: shows the title immediately and reserves a
            // row's worth of height so the layout stays stable and the fetch
            // isn't cancelled by recycling. Cards pop in when they arrive.
            Column(Modifier.padding(vertical = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 24.dp, bottom = 6.dp)
                )
                androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(226.dp))
            }
        }
        else -> if (loaded.isNotEmpty()) {
            MediaRow(title, loaded) { nav.navigate(Routes.detail(it.type, it.id)) }
        }
    }
}
