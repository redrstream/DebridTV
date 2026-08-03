package io.debridtv.app.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import io.debridtv.app.R
import io.debridtv.app.data.prefs.HistoryEntry
import io.debridtv.app.data.scraper.SourceRequest
import io.debridtv.app.di.ServiceLocator
import io.debridtv.app.domain.StreamSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var diagText: TextView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorBackBtn: Button
    private lateinit var upNextPanel: LinearLayout
    private lateinit var upNextText: TextView
    private lateinit var upNextPlay: Button
    private lateinit var upNextCancel: Button
    private val handler = Handler(Looper.getMainLooper())

    private var url: String = ""
    private lateinit var title: String
    private lateinit var key: String
    private lateinit var type: String
    private var poster: String? = null
    private var startMs: Long = 0
    private var preferSurround: Boolean = true
    private var subtitleUrls: List<String> = emptyList()
    private var subtitleLangs: List<String> = emptyList()
    private var dataSourceFactory: DefaultDataSource.Factory? = null

    // Ranked candidate sources for the current title, so a dead/broken source can
    // fall back to the next one without kicking the user back to the source list.
    private var srcHashes: List<String> = emptyList()
    private var srcNames: List<String> = emptyList()
    private var srcFileIdx: List<Int> = emptyList()
    private var epSeason: Int = -1
    private var epEpisode: Int = -1
    private var currentIndex: Int = 0
    private val triedIndices = mutableSetOf<Int>()
    private var fallbackJob: Job? = null

    // Be VERY patient on the source the user actually picked before ever switching to a
    // different one. AllDebrid reports a magnet "Ready" the instant its download finishes,
    // but a just-completed file often isn't reliably servable from the CDN for another
    // 20-40s. Falling back to a *different* source in that window is actively harmful — it
    // uploads another magnet and starts another download, competing for bandwidth so
    // nothing gets the few seconds it needs to settle. Instead we re-unlock the SAME source
    // repeatedly (idempotent — no new download) for ~50s, which is what makes a manual
    // "Play again" / opening it later from the Library reliably work. Only after this long
    // grace do we treat the pick as genuinely dead and try another.
    private var currentRetries = 0
    private val maxSourceRetries = 10
    private val retryDelayMs = 5_000L
    private val retrySource = Runnable { reprepareCurrent() }
    private var retryJob: Job? = null
    // What last forced a retry/switch — surfaced in the on-screen status so a real-TV test is
    // legible without adb: an error code ("bad source") vs "slow" (the stall watchdog).
    private var lastTrigger = ""

    // Persistent on-screen diagnostic log: the last several player events (source starts, error
    // codes, retries, switches, stalls) kept visible so a real-TV test is legible without adb.
    // The flashing status text was too brief to read; this stays put so it can be photographed.
    private val diagLog = ArrayDeque<String>()
    private var diagStart = 0L
    private fun diag(msg: String) {
        if (diagStart == 0L) diagStart = android.os.SystemClock.uptimeMillis()
        val t = (android.os.SystemClock.uptimeMillis() - diagStart) / 1000
        diagLog.addLast("+${t}s  $msg")
        while (diagLog.size > 10) diagLog.removeFirst()
        diagText.text = diagLog.joinToString("\n")
        diagText.visibility = View.VISIBLE
    }

    // Series binge state: the episodes queued after the current one, plus what we
    // need to scrape + resolve them when the current episode finishes.
    private var imdbId: String = ""
    private var showTitle: String = ""
    private var year: Int = -1
    private val nextSeasons = ArrayList<Int>()
    private val nextEpisodes = ArrayList<Int>()
    private val nextLabels = ArrayList<String>()
    private var advanceJob: Job? = null
    private var advancing = false
    private val autoAdvance = Runnable { playNextEpisode() }

    private val saveTick = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }

    // Stall watchdog: sample playback position every 10s. If the content advances
    // too little across a ~30s window (rebuffer loop or dead stall — which never
    // raise a PlaybackException), the source can't keep up, so switch to another.
    private val posHistory = ArrayDeque<Long>()
    private val stallCheck = object : Runnable {
        override fun run() {
            checkStall()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        statusText = findViewById(R.id.status_text)
        diagText = findViewById(R.id.diag_text)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        errorBackBtn = findViewById(R.id.error_back_btn)
        errorBackBtn.setOnClickListener { finish() }
        upNextPanel = findViewById(R.id.upnext_panel)
        upNextText = findViewById(R.id.upnext_text)
        upNextPlay = findViewById(R.id.upnext_play)
        upNextCancel = findViewById(R.id.upnext_cancel)
        upNextPlay.setOnClickListener { playNextEpisode() }
        upNextCancel.setOnClickListener {
            handler.removeCallbacks(autoAdvance)
            upNextPanel.visibility = View.GONE
        }
        playerView.setShowSubtitleButton(true)

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        poster = intent.getStringExtra(EXTRA_POSTER)
        startMs = intent.getLongExtra(EXTRA_START_MS, 0)
        preferSurround = intent.getBooleanExtra(EXTRA_SURROUND, true)
        subtitleUrls = intent.getStringArrayListExtra(EXTRA_SUB_URLS) ?: emptyList()
        subtitleLangs = intent.getStringArrayListExtra(EXTRA_SUB_LANGS) ?: emptyList()
        srcHashes = intent.getStringArrayListExtra(EXTRA_SRC_HASHES) ?: emptyList()
        srcNames = intent.getStringArrayListExtra(EXTRA_SRC_NAMES) ?: emptyList()
        srcFileIdx = intent.getIntegerArrayListExtra(EXTRA_SRC_FILEIDX) ?: emptyList()
        epSeason = intent.getIntExtra(EXTRA_EP_SEASON, -1)
        epEpisode = intent.getIntExtra(EXTRA_EP_EPISODE, -1)
        currentIndex = intent.getIntExtra(EXTRA_SRC_INDEX, 0)
        imdbId = intent.getStringExtra(EXTRA_IMDB_ID).orEmpty()
        showTitle = intent.getStringExtra(EXTRA_SHOW_TITLE).orEmpty()
        year = intent.getIntExtra(EXTRA_YEAR, -1)
        intent.getIntegerArrayListExtra(EXTRA_NEXT_SEASONS)?.let { nextSeasons.addAll(it) }
        intent.getIntegerArrayListExtra(EXTRA_NEXT_EPISODES)?.let { nextEpisodes.addAll(it) }
        intent.getStringArrayListExtra(EXTRA_NEXT_LABELS)?.let { nextLabels.addAll(it) }

        if (url.isBlank()) {
            Toast.makeText(this, "No stream URL", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        saveProgress()
        releasePlayer()
        super.onStop()
    }

    private fun initPlayer() {
        if (player != null) return
        diag("start #$currentIndex subs=${subtitleUrls.count { it.isNotBlank() }} ${(srcNames.getOrNull(currentIndex) ?: title).take(34)}")

        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                // Allow surround/Atmos tracks even if the TV can't decode them,
                // so the bitstream can pass through to an AVR/soundbar.
                .setConstrainAudioChannelCountToDeviceCapabilities(!preferSurround)
                .build()
        }

        // AllDebrid serves HTTPS direct links that often redirect to a CDN host.
        // Allow cross-protocol redirects and give the connection more than the
        // tight 8s default so a warming CDN edge isn't dropped prematurely. The
        // stream stays HTTPS — usesCleartextTraffic=false still blocks any http leg.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val dsFactory = DefaultDataSource.Factory(this, httpFactory)
        dataSourceFactory = dsFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)

        // Decoder fallback: if a specialised decoder can't handle a stream (e.g. a
        // Dolby Vision Profile 7 remux, which Android TVs like the Hisense U78QG
        // don't support), fall back to a plain HEVC decoder and play the HDR10
        // base layer instead of failing outright.
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        // Keep the START fast (5s, only a touch above the 2.5s default) — the resolver's
        // servable-probe already guarantees the link is streaming before we hit play, so the
        // initial buffer doesn't need to carry the safety burden. Put the cushion on the
        // RESUME-after-a-stall path (15s) instead: that only runs once a hitch has already
        // happened, so it doesn't slow a healthy start, and resuming on a real cushion rather
        // than a sliver is what breaks the load-stop-load loop. Total buffer stays ~50s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                5_000,
                15_000
            )
            .build()

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        player = exo
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Codec/format errors are inherent to the file — retrying won't help, so
                // switch sources immediately. Everything else (IO, parsing, a source still
                // settling / a warming CDN edge) gets the patient in-place retry first.
                lastTrigger = error.errorCodeName
                diag("ERR ${error.errorCodeName} transient=${isTransient(error)}")
                if (!isTransient(error)) {
                    val next = nextUntriedIndex()
                    if (next != null) fallbackTo(next, "Switching (${error.errorCodeName})…") else showError(error)
                    return
                }
                if (retryCurrent()) return
                val next = nextUntriedIndex()
                if (next != null) fallbackTo(next, "Switching (${error.errorCodeName})…") else showError(error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Playback actually started — reset the retry budget so a later hiccup
                // deeper into the stream gets its own fresh set of retries.
                if (state == Player.STATE_READY) currentRetries = 0
                when (state) {
                    Player.STATE_READY -> diag("playing")
                    Player.STATE_ENDED -> diag("ended")
                    Player.STATE_IDLE -> diag("idle")
                }
                if (!advancing) {
                    statusText.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_BUFFERING) statusText.text = "Buffering…"
                }
                if (state == Player.STATE_ENDED) offerNextEpisode()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // A manual seek isn't a stall — don't let it skew the progress window.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) posHistory.clear()
            }
        })

        exo.setMediaSource(buildMediaSource())
        exo.prepare()
        if (startMs > 5_000) exo.seekTo(startMs)
        exo.playWhenReady = true
        handler.postDelayed(saveTick, 10_000)
        handler.postDelayed(stallCheck, 10_000)
    }

    /**
     * Give the CURRENT (user-picked) source another in-place re-unlock instead of jumping to
     * a different source. Shared by BOTH switch-away mechanisms — a hard error ("bad source",
     * onPlayerError) and the stall watchdog ("too slow", checkStall) — so neither can abandon
     * the pick prematurely, which would only spawn a competing AllDebrid download. Returns
     * false when the retry budget is spent and the caller should fall back to another source.
     */
    private fun retryCurrent(): Boolean {
        if (currentRetries >= maxSourceRetries) return false
        currentRetries++
        diag("retry $currentRetries/$maxSourceRetries [$lastTrigger]")
        statusText.visibility = View.VISIBLE
        val why = lastTrigger.ifBlank { "buffering" }
        statusText.text = "Buffering… (retry $currentRetries/$maxSourceRetries · $why)"
        handler.removeCallbacks(retrySource)
        handler.postDelayed(retrySource, retryDelayMs)
        return true
    }

    /**
     * Retry the current source after a transient error. Crucially this RE-RESOLVES the
     * source (a fresh AllDebrid unlock → a new CDN link) rather than re-preparing the same
     * URL: AllDebrid hands back a link before its edge is warm, and that specific link can
     * keep failing while a freshly-minted one routes to a warm edge. This mirrors what a
     * manual "back → Play again" does (which is why that reliably works). If re-resolving
     * throws, we fall back on the same URL we already have so a resolver blip isn't fatal.
     */
    private fun reprepareCurrent() {
        val p = player ?: return
        val resumeAt = (p.currentPosition).takeIf { it > 5_000 } ?: startMs
        diag("re-unlock same #$currentIndex")
        statusText.visibility = View.VISIBLE
        statusText.text = "Reconnecting…"
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            try {
                if (srcHashes.isNotEmpty() && currentIndex in srcHashes.indices) {
                    val src = StreamSource(
                        infoHash = srcHashes[currentIndex],
                        fileIdx = srcFileIdx.getOrNull(currentIndex)?.takeIf { it >= 0 },
                        filename = srcNames.getOrNull(currentIndex) ?: srcHashes[currentIndex],
                        quality = "", sizeText = null, seeders = null, provider = null, rawTitle = ""
                    )
                    val hint = if (epSeason >= 0 && epEpisode >= 0) epSeason to epEpisode else null
                    url = ServiceLocator.resolver.resolve(src, hint).url
                }
            } catch (_: Exception) {
                // Keep the existing url and re-prepare it anyway — better than aborting the retry.
            }
            val pl = player ?: return@launch
            statusText.text = "Buffering…"
            pl.setMediaSource(buildMediaSource())
            pl.prepare()
            if (resumeAt > 5_000) pl.seekTo(resumeAt)
            pl.playWhenReady = true
        }
    }

    /** Whether an error is worth retrying on the same source (transient) vs. moving on. */
    private fun isTransient(error: PlaybackException): Boolean {
        val name = error.errorCodeName
        // A codec/format problem is inherent to the file — retrying won't help, switch sources.
        return !(name.contains("DECOD", ignoreCase = true) || name.contains("FORMAT", ignoreCase = true))
    }

    /** Switch away from a source that's playing but can't sustain real-time playback. */
    private fun checkStall() {
        val p = player ?: return
        if (!p.playWhenReady || advancing ||
            errorPanel.visibility == View.VISIBLE || upNextPanel.visibility == View.VISIBLE
        ) {
            posHistory.clear()
            return
        }
        posHistory.addLast(p.currentPosition)
        while (posHistory.size > 4) posHistory.removeFirst()
        if (posHistory.size == 4) {
            val progressed = posHistory.last() - posHistory.first()  // content gained over ~30s wall
            if (progressed < 12_000) {  // under ~12s of video in 30s — can't keep up
                posHistory.clear()
                lastTrigger = "slow"
                diag("stall ${progressed}ms/30s")
                // "Too slow" is usually a freshly-completed source still settling on the CDN,
                // not a genuinely bad one — so re-unlock the SAME source (a fresh link may hit
                // a warmer edge) rather than switching, which would spawn a competing download.
                // Only once the shared patience budget is spent do we try a different source.
                if (retryCurrent()) return
                val next = nextUntriedIndex()
                if (next != null) {
                    fallbackTo(next, "Source too slow — switching…")
                } else {
                    // Nothing faster to try; keep buffering rather than kill playback.
                    Toast.makeText(
                        this,
                        "This source is slow and there are no faster ones to try.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Next candidate we haven't attempted yet (marks the current one as tried). */
    private fun nextUntriedIndex(): Int? {
        if (srcHashes.isEmpty()) return null
        triedIndices.add(currentIndex)
        return srcHashes.indices.firstOrNull { it !in triedIndices }
    }

    /**
     * Resolve and switch to another source in place — no trip back to the source list.
     * Keeps subtitles and playback position; if this one also can't resolve, rolls on
     * to the next, and only surfaces the error panel once every source is exhausted.
     */
    private fun fallbackTo(index: Int, statusMsg: String = "Source failed — trying another…") {
        diag("switch → #$index ${(srcNames.getOrNull(index) ?: "?").take(34)}")
        currentIndex = index
        triedIndices.add(index)
        currentRetries = 0
        handler.removeCallbacks(retrySource)
        retryJob?.cancel()
        posHistory.clear()
        val resumeAt = (player?.currentPosition ?: 0L).takeIf { it > 5_000 } ?: startMs
        errorPanel.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = statusMsg
        fallbackJob?.cancel()
        fallbackJob = lifecycleScope.launch {
            try {
                val src = StreamSource(
                    infoHash = srcHashes[index],
                    fileIdx = srcFileIdx.getOrNull(index)?.takeIf { it >= 0 },
                    filename = srcNames.getOrNull(index) ?: srcHashes[index],
                    quality = "", sizeText = null, seeders = null, provider = null, rawTitle = ""
                )
                val hint = if (epSeason >= 0 && epEpisode >= 0) epSeason to epEpisode else null
                val resolved = ServiceLocator.resolver.resolve(src, hint)
                val p = player ?: return@launch
                url = resolved.url
                statusText.visibility = View.GONE
                p.setMediaSource(buildMediaSource())
                p.prepare()
                if (resumeAt > 5_000) p.seekTo(resumeAt)
                p.playWhenReady = true
            } catch (e: Exception) {
                diag("resolve fail: ${(e.message ?: e.javaClass.simpleName).take(30)}")
                val next = nextUntriedIndex()
                if (next != null) {
                    fallbackTo(next)
                } else {
                    diag("EXHAUSTED: no working source")
                    statusText.visibility = View.GONE
                    playerView.hideController()
                    playerView.useController = false
                    errorText.text = "Couldn't find a working source for this. Try again later."
                    errorPanel.visibility = View.VISIBLE
                    errorBackBtn.requestFocus()
                }
            }
        }
    }

    /** Episode finished: show an "Up next" prompt that auto-advances after a few seconds. */
    private fun offerNextEpisode() {
        if (nextSeasons.isEmpty() || nextLabels.isEmpty()) return
        saveProgress()
        upNextText.text = "Up next: ${nextLabels.first()}"
        upNextPanel.visibility = View.VISIBLE
        upNextPlay.requestFocus()
        handler.removeCallbacks(autoAdvance)
        handler.postDelayed(autoAdvance, 8_000)
    }

    /** Scrape + resolve the next queued episode and swap it into the current player. */
    private fun playNextEpisode() {
        handler.removeCallbacks(autoAdvance)
        if (nextSeasons.isEmpty()) return
        val season = nextSeasons.removeAt(0)
        val episode = nextEpisodes.removeAt(0)
        val label = if (nextLabels.isNotEmpty()) nextLabels.removeAt(0) else "S${season}E${episode}"

        advancing = true
        currentRetries = 0
        handler.removeCallbacks(retrySource)
        retryJob?.cancel()
        posHistory.clear()
        upNextPanel.visibility = View.GONE
        errorPanel.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Loading $label…"
        advanceJob?.cancel()
        advanceJob = lifecycleScope.launch {
            try {
                val request = SourceRequest(
                    type = "series",
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    title = showTitle,
                    year = year.takeIf { it > 0 }
                )
                val found = ServiceLocator.mediaRepo.sources(request)
                if (found.isEmpty()) throw IllegalStateException("No sources for $label")

                // Re-arm the fallback list for the new episode.
                srcHashes = found.map { it.infoHash }
                srcNames = found.map { it.filename }
                srcFileIdx = found.map { it.fileIdx ?: -1 }
                currentIndex = 0
                triedIndices.clear()
                epSeason = season
                epEpisode = episode
                startMs = 0  // new episode starts from the beginning

                val resolved = ServiceLocator.resolver.resolve(found.first(), season to episode)
                val cid = "$imdbId:$season:$episode"
                val subs = ServiceLocator.mediaRepo.subtitles("series", cid)

                val p = player ?: return@launch
                url = resolved.url
                key = cid
                title = "$showTitle · $label"
                subtitleUrls = subs.map { it.url }
                subtitleLangs = subs.map { it.lang }
                advancing = false
                statusText.visibility = View.GONE
                p.setMediaSource(buildMediaSource())
                p.prepare()
                p.playWhenReady = true
            } catch (e: Exception) {
                advancing = false
                statusText.visibility = View.GONE
                playerView.hideController()
                playerView.useController = false
                errorText.text = "Couldn't load the next episode. Back to sources to pick one."
                errorPanel.visibility = View.VISIBLE
                errorBackBtn.requestFocus()
            }
        }
    }

    /**
     * A stream failed mid-playback (dead AllDebrid link, source not really cached,
     * unsupported codec…). Replace the black screen with a plain-language message and
     * a focused button back to the source list so another can be picked.
     */
    private fun showError(error: PlaybackException) {
        diag("ERROR PANEL: ${error.errorCodeName}")
        statusText.visibility = View.GONE
        playerView.hideController()
        playerView.useController = false
        errorText.text = friendlyError(error)
        errorPanel.visibility = View.VISIBLE
        errorBackBtn.requestFocus()
    }

    private fun friendlyError(error: PlaybackException): String {
        val name = error.errorCodeName
        return when {
            name.contains("DECOD", ignoreCase = true) || name.contains("FORMAT", ignoreCase = true) ->
                "This file's format can't be played on this device. Try another source."
            name.contains("IO", ignoreCase = true) || name.contains("SOURCE", ignoreCase = true) ->
                "Couldn't load this source — it may be offline or still caching on AllDebrid. Try another source."
            else -> "This source didn't play. Try another source."
        }
    }

    /**
     * Build the media source for the current [url] plus any sideloaded subtitles.
     *
     * CRUCIAL: each subtitle is merged as its own SingleSampleMediaSource with
     * treatLoadErrorsAsEndOfStream(true), so a bad/expired/rate-limited subtitle URL can
     * NEVER take playback down with it. Attaching subtitles via MediaItem.SubtitleConfiguration
     * (the previous approach) made a subtitle load error fatal — which is exactly why the SAME
     * source played fine from the Library (no subtitles attached) but load-stop-looped from the
     * Detail Play button (subtitles attached): the sub failed, the player errored, we re-resolved
     * the same source with the same broken sub, and looped forever.
     */
    private fun buildMediaSource(): MediaSource {
        val ds = dataSourceFactory ?: DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory())
        val videoSource = DefaultMediaSourceFactory(ds)
            .createMediaSource(MediaItem.fromUri(url))
        val subSources = subtitleUrls.mapIndexedNotNull { i, u ->
            if (u.isBlank()) return@mapIndexedNotNull null
            val mime = if (u.endsWith(".vtt", ignoreCase = true))
                MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                .setMimeType(mime)
                .setLanguage(subtitleLangs.getOrNull(i) ?: "und")
                .build()
            SingleSampleMediaSource.Factory(ds)
                .setTreatLoadErrorsAsEndOfStream(true)
                .createMediaSource(cfg, C.TIME_UNSET)
        }
        return if (subSources.isEmpty()) videoSource
        else MergingMediaSource(videoSource, *subSources.toTypedArray())
    }

    private fun saveProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = if (p.duration > 0) p.duration else 0L
        if (pos <= 0 || key.isBlank()) return
        val entry = HistoryEntry(
            key = key,
            type = type,
            title = title,
            poster = poster,
            positionMs = pos,
            durationMs = dur,
            updatedAt = System.currentTimeMillis()
        )
        lifecycleScope.launch { ServiceLocator.history.upsert(entry) }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(autoAdvance)
        handler.removeCallbacks(stallCheck)
        handler.removeCallbacks(retrySource)
        retryJob?.cancel()
        fallbackJob?.cancel()
        advanceJob?.cancel()
        player?.release()
        player = null
        playerView.player = null
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_START_MS = "start_ms"
        private const val EXTRA_SURROUND = "surround"
        private const val EXTRA_SUB_URLS = "sub_urls"
        private const val EXTRA_SUB_LANGS = "sub_langs"
        private const val EXTRA_SRC_HASHES = "src_hashes"
        private const val EXTRA_SRC_NAMES = "src_names"
        private const val EXTRA_SRC_FILEIDX = "src_fileidx"
        private const val EXTRA_SRC_INDEX = "src_index"
        private const val EXTRA_EP_SEASON = "ep_season"
        private const val EXTRA_EP_EPISODE = "ep_episode"
        private const val EXTRA_IMDB_ID = "imdb_id"
        private const val EXTRA_SHOW_TITLE = "show_title"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_NEXT_SEASONS = "next_seasons"
        private const val EXTRA_NEXT_EPISODES = "next_episodes"
        private const val EXTRA_NEXT_LABELS = "next_labels"

        fun start(
            context: Context,
            url: String,
            title: String,
            key: String,
            type: String,
            poster: String?,
            startMs: Long,
            preferSurround: Boolean = true,
            subtitleUrls: List<String> = emptyList(),
            subtitleLangs: List<String> = emptyList(),
            sourceHashes: List<String> = emptyList(),
            sourceNames: List<String> = emptyList(),
            sourceFileIdx: List<Int> = emptyList(),
            sourceIndex: Int = 0,
            epSeason: Int = -1,
            epEpisode: Int = -1,
            imdbId: String = "",
            showTitle: String = "",
            year: Int = -1,
            nextSeasons: List<Int> = emptyList(),
            nextEpisodes: List<Int> = emptyList(),
            nextLabels: List<String> = emptyList()
        ) {
            val i = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_POSTER, poster)
                putExtra(EXTRA_START_MS, startMs)
                putExtra(EXTRA_SURROUND, preferSurround)
                putStringArrayListExtra(EXTRA_SUB_URLS, ArrayList(subtitleUrls))
                putStringArrayListExtra(EXTRA_SUB_LANGS, ArrayList(subtitleLangs))
                putStringArrayListExtra(EXTRA_SRC_HASHES, ArrayList(sourceHashes))
                putStringArrayListExtra(EXTRA_SRC_NAMES, ArrayList(sourceNames))
                putIntegerArrayListExtra(EXTRA_SRC_FILEIDX, ArrayList(sourceFileIdx))
                putExtra(EXTRA_SRC_INDEX, sourceIndex)
                putExtra(EXTRA_EP_SEASON, epSeason)
                putExtra(EXTRA_EP_EPISODE, epEpisode)
                putExtra(EXTRA_IMDB_ID, imdbId)
                putExtra(EXTRA_SHOW_TITLE, showTitle)
                putExtra(EXTRA_YEAR, year)
                putIntegerArrayListExtra(EXTRA_NEXT_SEASONS, ArrayList(nextSeasons))
                putIntegerArrayListExtra(EXTRA_NEXT_EPISODES, ArrayList(nextEpisodes))
                putStringArrayListExtra(EXTRA_NEXT_LABELS, ArrayList(nextLabels))
            }
            context.startActivity(i)
        }
    }
}
