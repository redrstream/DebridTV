package io.debridtv.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import io.debridtv.app.data.cinemeta.Meta
import io.debridtv.app.data.cinemeta.Video
import io.debridtv.app.data.scraper.SourceRequest
import io.debridtv.app.di.ServiceLocator
import io.debridtv.app.domain.StreamSource
import io.debridtv.app.ui.components.TvButton
import io.debridtv.app.ui.components.TvOutlinedButton
import io.debridtv.app.ui.components.tvFocusRing
import io.debridtv.app.ui.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A season/episode coordinate, ordered so we can compare "which comes first". */
private data class EpKey(val season: Int, val episode: Int) : Comparable<EpKey> {
    override fun compareTo(other: EpKey): Int =
        compareValuesBy(this, other, { it.season }, { it.episode })
}

/** Where the Resume button should drop the user, and at what position. */
private data class ResumeInfo(val season: Int, val episode: Int, val positionMs: Long)

private enum class DetailLoad { LOADING, READY, ERROR }

@Composable
fun DetailScreen(
    nav: NavHostController,
    type: String,
    id: String,
    season: Int? = null,
    episode: Int? = null
) {
    val context = LocalContext.current
    val repo = ServiceLocator.mediaRepo
    val scope = rememberCoroutineScope()
    val apiKey by ServiceLocator.settings.apiKey.collectAsState(initial = null)
    val preferSurround by ServiceLocator.settings.preferSurround.collectAsState(initial = true)
    val history by ServiceLocator.history.history.collectAsState(initial = emptyList())

    BackHandler { nav.popBackStack() }

    var meta by remember { mutableStateOf<Meta?>(null) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var currentVideo by remember { mutableStateOf<Video?>(null) }
    var sources by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }
    var pendingPlay by remember { mutableStateOf(false) }
    var markTarget by remember { mutableStateOf<Video?>(null) }
    var metaLoad by remember { mutableStateOf(DetailLoad.LOADING) }
    var retryTick by remember { mutableStateOf(0) }
    val backFocus = remember { FocusRequester() }
    val resumeFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    val isSeries = type == "series"

    LaunchedEffect(type, id, retryTick) {
        metaLoad = DetailLoad.LOADING
        val m = repo.meta(type, id)
        meta = m
        metaLoad = if (m == null) DetailLoad.ERROR else DetailLoad.READY
    }

    // On failure, focus the Retry button so it's actionable with one press.
    LaunchedEffect(metaLoad) {
        if (metaLoad == DetailLoad.ERROR) {
            delay(120)
            runCatching { retryFocus.requestFocus() }
        }
    }

    // --- Watched / resume model -------------------------------------------------
    // History rows for this title: the movie itself ("tt123") or its episodes
    // ("tt123:season:episode").
    val showEntries = remember(history, id) {
        history.filter { it.key == id || it.key.startsWith("$id:") }
    }
    val entriesByKey: Map<EpKey, io.debridtv.app.data.prefs.HistoryEntry> = remember(showEntries) {
        showEntries.mapNotNull { e ->
            val p = e.key.split(":")
            val s = p.getOrNull(1)?.toIntOrNull()
            val ep = p.getOrNull(2)?.toIntOrNull()
            if (s != null && ep != null) EpKey(s, ep) to e else null
        }.toMap()
    }
    val orderedEps: List<EpKey> = remember(meta) {
        meta?.videos
            ?.filter { (it.season ?: 0) > 0 && it.episodeNumber != null }
            ?.map { EpKey(it.season!!, it.episodeNumber!!) }
            ?.distinct()?.sorted().orEmpty()
    }
    // The episode we're "up to": the one deep-linked from Continue Watching, or
    // otherwise the most recently watched episode of this show.
    val anchor: EpKey? = remember(season, episode, showEntries, id) {
        if (season != null && episode != null) EpKey(season, episode)
        else showEntries
            .filter { it.key.startsWith("$id:") }
            .maxByOrNull { it.updatedAt }
            ?.let { e ->
                val p = e.key.split(":")
                val s = p.getOrNull(1)?.toIntOrNull(); val ep = p.getOrNull(2)?.toIntOrNull()
                if (s != null && ep != null) EpKey(s, ep) else null
            }
    }
    val anchorEntry = anchor?.let { entriesByKey[it] }
    // Within the last 5 min (or manually marked) => that episode is done, roll on.
    val anchorDone = anchorEntry?.isWatched == true
    val resume: ResumeInfo? = when {
        !isSeries -> null
        anchor == null -> null
        anchorDone -> orderedEps.firstOrNull { it > anchor }
            ?.let { ResumeInfo(it.season, it.episode, 0L) }
        else -> ResumeInfo(anchor.season, anchor.episode, anchorEntry?.positionMs ?: 0L)
    }
    // Every episode at or before this coordinate is shown as watched. Reaching a
    // later episode implies the earlier ones are done, without per-episode history.
    val impliedWatchedUpTo: EpKey? = when {
        anchor == null -> null
        anchorDone -> anchor
        else -> orderedEps.lastOrNull { it < anchor }
    }
    fun episodeWatched(ep: Video): Boolean {
        val s = ep.season ?: return false
        val e = ep.episodeNumber ?: return false
        val k = EpKey(s, e)
        return entriesByKey[k]?.isWatched == true ||
            (impliedWatchedUpTo != null && k <= impliedWatchedUpTo)
    }
    val movieEntry = if (!isSeries) showEntries.firstOrNull { it.key == id } else null
    val canResumeMovie = movieEntry != null && !movieEntry.isWatched && movieEntry.progress > 0.02f
    val resumeLabel: String? = when {
        isSeries && resume != null -> "Resume  S${resume.season} · E${resume.episode}"
        canResumeMovie -> "Resume"
        else -> null
    }

    // Pre-select the resume episode so its sources start loading immediately.
    LaunchedEffect(meta, resume) {
        if (!isSeries) return@LaunchedEffect
        val vids = meta?.videos ?: return@LaunchedEffect
        if (selectedSeason == null) selectedSeason = resume?.season ?: orderedEps.firstOrNull()?.season
        if (currentVideo == null && resume != null) {
            currentVideo = vids.firstOrNull { it.season == resume.season && it.episodeNumber == resume.episode }
        }
    }

    // Focus once, on the Resume button if there's something to resume, else Back.
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(meta, resumeLabel) {
        if (focusedOnce || meta == null) return@LaunchedEffect
        delay(150)
        focusedOnce = true
        runCatching { (if (resumeLabel != null) resumeFocus else backFocus).requestFocus() }
    }

    // The content id Torrentio expects, and the resume key.
    val contentId: String? = when {
        !isSeries -> id
        else -> currentVideo?.let { v ->
            val s = v.season
            val e = v.episodeNumber
            if (s != null && e != null) "$id:$s:$e" else null
        }
    }

    LaunchedEffect(contentId, meta?.name) {
        if (contentId == null) return@LaunchedEffect
        sourcesLoading = true
        val request = SourceRequest(
            type = type,
            imdbId = id,
            season = currentVideo?.season,
            episode = currentVideo?.episodeNumber,
            title = meta?.name,
            year = meta?.releaseInfo?.take(4)?.toIntOrNull()
        )
        sources = repo.sources(request)
        sourcesLoading = false
        // Auto-queue: start caching the top source so it's ready before playback.
        if (!apiKey.isNullOrBlank()) {
            sources.firstOrNull()?.let { top ->
                scope.launch { runCatching { ServiceLocator.resolver.preload(top) } }
            }
        }
    }

    fun play(source: StreamSource) {
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(context, "Add your AllDebrid API key in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        val cid = contentId ?: return
        val m = meta
        val title = if (isSeries && currentVideo != null)
            "${m?.name ?: ""} · ${currentVideo!!.displayLabel}" else (m?.name ?: "")
        val episodeHint = if (isSeries) currentVideo?.let { v ->
            val s = v.season; val e = v.episodeNumber
            if (s != null && e != null) s to e else null
        } else null

        status = "Starting…"
        resolveJob = scope.launch {
            try {
                val start = ServiceLocator.history.get(cid)?.positionMs ?: 0L
                val resolved = ServiceLocator.resolver.resolve(source, episodeHint) { status = it }
                status = "Loading subtitles…"
                val subs = ServiceLocator.mediaRepo.subtitles(type, cid)
                status = null

                // Episodes queued after this one, so the player can auto-play next.
                val cv = currentVideo
                val upcoming = if (isSeries && cv != null) {
                    meta?.videos
                        ?.filter { it.season != null && it.episodeNumber != null }
                        ?.sortedWith(compareBy({ it.season }, { it.episodeNumber }))
                        .orEmpty()
                        .dropWhile { !(it.season == cv.season && it.episodeNumber == cv.episodeNumber) }
                        .drop(1)
                } else emptyList()

                PlayerActivity.start(
                    context = context,
                    url = resolved.url,
                    title = title,
                    key = cid,
                    type = type,
                    poster = m?.poster,
                    startMs = start,
                    preferSurround = preferSurround,
                    subtitleUrls = subs.map { it.url },
                    subtitleLangs = subs.map { it.lang },
                    // Ranked candidates so the player can self-heal a dead source.
                    sourceHashes = sources.map { it.infoHash },
                    sourceNames = sources.map { it.filename },
                    sourceFileIdx = sources.map { it.fileIdx ?: -1 },
                    sourceIndex = sources.indexOf(source).coerceAtLeast(0),
                    epSeason = episodeHint?.first ?: -1,
                    epEpisode = episodeHint?.second ?: -1,
                    imdbId = id,
                    showTitle = m?.name ?: "",
                    year = m?.releaseInfo?.take(4)?.toIntOrNull() ?: -1,
                    nextSeasons = upcoming.mapNotNull { it.season },
                    nextEpisodes = upcoming.mapNotNull { it.episodeNumber },
                    nextLabels = upcoming.map { it.displayLabel }
                )
            } catch (e: Exception) {
                status = null
                Toast.makeText(context, e.message ?: "Failed to start stream", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Resume button sets pendingPlay; once the (correct episode's) sources have
    // finished loading, kick off playback of the top source automatically.
    LaunchedEffect(pendingPlay, sources, sourcesLoading) {
        if (pendingPlay && !sourcesLoading && sources.isNotEmpty()) {
            pendingPlay = false
            play(sources.first())
        }
    }

    fun resumeNow() {
        if (isSeries) {
            resume?.let { r ->
                val ep = meta?.videos?.firstOrNull { it.season == r.season && it.episodeNumber == r.episode }
                if (ep != null && currentVideo?.id != ep.id) {
                    selectedSeason = r.season
                    currentVideo = ep
                }
            }
        }
        // If the right sources are already loaded, play immediately; otherwise wait.
        if (!sourcesLoading && sources.isNotEmpty()) play(sources.first()) else pendingPlay = true
    }

    fun queue(source: StreamSource) {
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(context, "Add your AllDebrid API key in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            runCatching { ServiceLocator.resolver.preload(source) }
                .onSuccess { Toast.makeText(context, "Queued on AllDebrid", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Queue failed", Toast.LENGTH_LONG).show() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (metaLoad != DetailLoad.READY) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                TvButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.focusRequester(backFocus).padding(bottom = 12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        modifier = Modifier.padding(end = 6.dp))
                    Text("Back")
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (metaLoad == DetailLoad.LOADING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp))
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Couldn't load this title — check your connection.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TvButton(
                                onClick = { retryTick++ },
                                modifier = Modifier.focusRequester(retryFocus).padding(top = 12.dp)
                            ) { Text("Retry") }
                        }
                    }
                }
            }
        } else {
        LazyColumn(Modifier.fillMaxSize().padding(24.dp)) {
            item {
                TvButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier
                        .focusRequester(backFocus)
                        .padding(bottom = 12.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("Back")
                }
            }
            if (resumeLabel != null) {
                item {
                    ResumeButton(
                        label = resumeLabel,
                        focusRequester = resumeFocus,
                        onClick = { resumeNow() }
                    )
                }
            }
            item { Header(meta) }

            if (isSeries) {
                val seasons = meta?.videos?.mapNotNull { it.season }?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
                if (seasons.isNotEmpty()) {
                    item {
                        Text("Seasons", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { s ->
                                // Highlight the active season (filled) vs the rest (outlined)
                                // so it's obvious which season's episodes are shown below.
                                if (s == selectedSeason) {
                                    TvButton(onClick = { selectedSeason = s; currentVideo = null }) {
                                        Text("Season $s")
                                    }
                                } else {
                                    TvOutlinedButton(onClick = { selectedSeason = s; currentVideo = null }) {
                                        Text("Season $s")
                                    }
                                }
                            }
                        }
                    }
                    val episodes = meta?.videos
                        ?.filter { it.season == selectedSeason }
                        ?.sortedBy { it.episodeNumber ?: 0 }
                        .orEmpty()
                    item {
                        val epHeader = selectedSeason
                            ?.let { "Season $it · ${episodes.size} episodes" }
                            ?: "Episodes"
                        Text(epHeader, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                    }
                    items(episodes, key = { it.id }) { ep ->
                        val watched = episodeWatched(ep)
                        val epKey = ep.season?.let { s -> ep.episodeNumber?.let { e -> EpKey(s, e) } }
                        val entry = epKey?.let { entriesByKey[it] }
                        // Show a resume bar only for a partially-watched, not-yet-done episode.
                        val prog = if (!watched && entry != null && entry.progress > 0.02f) entry.progress else 0f
                        EpisodeItem(
                            label = ep.displayLabel,
                            selected = currentVideo?.id == ep.id,
                            watched = watched,
                            progress = prog,
                            onClick = { currentVideo = ep },
                            onLongClick = { markTarget = ep }
                        )
                    }
                }
            }

            item {
                val cv = currentVideo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp)
                ) {
                    Text(
                        if (isSeries && cv == null) "Pick an episode to see sources" else "Sources",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    // Reliable, D-pad-reachable way to toggle the selected episode
                    // watched (long-pressing an episode row does the same).
                    if (isSeries && cv != null) {
                        Spacer(Modifier.width(16.dp))
                        val watched = episodeWatched(cv)
                        ActionChip(
                            label = if (watched) "Watched" else "Mark watched",
                            icon = Icons.Filled.Check,
                            primary = watched,
                            onClick = {
                                val k = "$id:${cv.season}:${cv.episodeNumber}"
                                val label = "${meta?.name ?: ""} · ${cv.displayLabel}"
                                scope.launch {
                                    ServiceLocator.history.setWatched(k, "series", label, meta?.poster, !watched)
                                }
                            }
                        )
                    }
                }
            }
            if (sourcesLoading) {
                item { Text("Finding sources…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (contentId != null && sources.isEmpty()) {
                item { Text("No sources found", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(sources, key = { it.infoHash }) { src ->
                SourceRow(src, onPlay = { play(src) }, onQueue = { queue(src) })
            }
        }
        }

        status?.let { s ->
            ResolvingOverlay(status = s, onCancel = {
                resolveJob?.cancel()
                status = null
            })
        }

        markTarget?.let { ep ->
            val currentlyWatched = episodeWatched(ep)
            AlertDialog(
                onDismissRequest = { markTarget = null },
                title = { Text(if (currentlyWatched) "Mark as unwatched?" else "Mark as watched?") },
                text = { Text(ep.displayLabel) },
                confirmButton = {
                    TvButton(onClick = {
                        val k = "$id:${ep.season}:${ep.episodeNumber}"
                        val label = "${meta?.name ?: ""} · ${ep.displayLabel}"
                        scope.launch {
                            ServiceLocator.history.setWatched(k, "series", label, meta?.poster, !currentlyWatched)
                        }
                        markTarget = null
                    }) { Text(if (currentlyWatched) "Mark unwatched" else "Mark watched") }
                },
                dismissButton = {
                    TvOutlinedButton(onClick = { markTarget = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun Header(meta: Meta?) {
    Row {
        val poster = meta?.poster
        Box(
            Modifier.width(130.dp).height(195.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!poster.isNullOrBlank()) {
                AsyncImage(model = poster, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(start = 20.dp)) {
            Text(meta?.name ?: "…", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            val sub = listOfNotNull(
                meta?.releaseInfo,
                meta?.runtime,
                meta?.imdbRating?.let { "★ $it" },
                meta?.genres?.take(3)?.joinToString(", ")?.ifBlank { null }
            ).joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
            meta?.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun SourceRow(src: StreamSource, onPlay: () -> Unit, onQueue: () -> Unit) {
    val meta = listOfNotNull(
        src.quality,
        src.sizeText,
        src.seeders?.let { "👤 $it" },
        src.provider
    ).joinToString("   ")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(meta, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(src.filename, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // Play: resolves the source (caching it on AllDebrid first if needed) and
        // starts playback as soon as it's ready. Queue: just caches for later.
        ActionChip(label = "Play", icon = Icons.Filled.PlayArrow, primary = true, onClick = onPlay)
        Spacer(Modifier.width(8.dp))
        ActionChip(label = "Queue", icon = Icons.Filled.Download, primary = false, onClick = onQueue)
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // No graphicsLayer scale on this clickable Row: it would distort the focus rect and
    // trap D-pad navigation between stacked source rows. Colour + ring signal focus.
    Row(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .tvFocusRing(focused, RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResumeButton(
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        Modifier
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .tvFocusRing(focused, RoundedCornerShape(24.dp))
            .focusRequester(focusRequester)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeItem(
    label: String,
    selected: Boolean,
    watched: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            // Watched episodes read as "done" by dimming; the focused one is always
            // full-brightness so you can still see where you are. Alpha only (no scale):
            // a scale on this focusable Box would distort the focus rect and trap D-pad
            // navigation between episodes. The border + background signal focus instead.
            .graphicsLayer {
                alpha = if (watched && !focused) 0.5f else 1f
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.surface
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.background
                }
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(8.dp)
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (watched) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Watched",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // Resume bar for a partially-watched episode.
            if (progress > 0f) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResolvingOverlay(status: String, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(status, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 16.dp))
            TvButton(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) { Text("Cancel") }
        }
    }
}
