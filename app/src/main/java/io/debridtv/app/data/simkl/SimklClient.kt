package io.debridtv.app.data.simkl

import io.debridtv.app.BuildConfig
import io.debridtv.app.data.prefs.HistoryEntry
import io.debridtv.app.data.prefs.HistoryStore
import io.debridtv.app.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cross-device watch sync via SimKL. A *mirror* layer: the local [HistoryStore]
 * stays the single source of truth for the player, and every network call here
 * is best-effort and swallowed on failure so nothing can break playback.
 *
 * - Push: [fireStart]/[firePause]/[fireStop] scrobble what you're watching and
 *   where you paused (so leaving one TV records your spot).
 * - Pull: [firePull]/[pullPlayback] fetches SimKL's resume points and merges the
 *   newer ones into local history (so another TV shows the show in Continue
 *   Watching at roughly the same spot).
 *
 * Only a client id is needed (SimKL's PIN flow uses no secret and its tokens
 * don't expire). When the id is absent the whole feature is inert and Settings
 * shows "not configured in this build".
 */
class SimklClient(
    private val api: SimklApi,
    private val settings: SettingsStore,
    private val history: HistoryStore,
    // Best-effort poster lookup for shows first seen on another device, so the
    // Continue Watching card isn't a blank placeholder. Cinemeta-backed.
    private val posterFor: suspend (type: String, imdb: String) -> String?
) {
    private val clientId = BuildConfig.SIMKL_CLIENT_ID

    // Application-lifetime scope so a scrobble started as the user leaves the
    // player survives the Activity being torn down (lifecycleScope would cancel).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isConfigured(): Boolean = clientId.isNotBlank()

    // ---- PIN device-flow login --------------------------------------------

    suspend fun requestPin(): SimklPinResp = withContext(Dispatchers.IO) { api.requestPin() }

    sealed interface PollResult {
        data object Success : PollResult
        data object Pending : PollResult
        data class Error(val message: String) : PollResult
    }

    /** One poll of the PIN endpoint. Caller loops on [PollResult.Pending] until
     *  the code's expiry. */
    suspend fun pollForToken(userCode: String): PollResult = withContext(Dispatchers.IO) {
        val poll = try {
            api.pollPin(userCode)
        } catch (_: Exception) {
            // A transient failure (or a not-yet-authorized 404) — keep waiting.
            return@withContext PollResult.Pending
        }
        val token = poll.access_token
        if (poll.result == "OK" && !token.isNullOrBlank()) {
            settings.setSimklToken(token)
            PollResult.Success
        } else {
            PollResult.Pending
        }
    }

    suspend fun disconnect() = settings.clearSimkl()

    private fun bearer(token: String) = "Bearer $token"

    private suspend fun validToken(): String? =
        settings.simklTokenOrNull()?.takeIf { it.isNotBlank() }

    // ---- Scrobble (push) ---------------------------------------------------

    fun fireStart(entry: HistoryEntry, progress: Double) = fire(entry, progress, Action.START)
    fun firePause(entry: HistoryEntry, progress: Double) = fire(entry, progress, Action.PAUSE)
    fun fireStop(entry: HistoryEntry, progress: Double) = fire(entry, progress, Action.STOP)

    private enum class Action { START, PAUSE, STOP }

    private fun fire(entry: HistoryEntry, progress: Double, action: Action) {
        scope.launch { scrobble(entry, progress, action) }
    }

    private suspend fun scrobble(entry: HistoryEntry, progress: Double, action: Action) {
        if (!isConfigured() || !settings.simklEnabledOrDefault()) return
        val body = scrobbleBody(entry, progress.coerceIn(0.0, 100.0)) ?: return
        val token = validToken() ?: return
        val auth = bearer(token)
        try {
            when (action) {
                Action.START -> api.scrobbleStart(auth, body)
                Action.PAUSE -> api.scrobblePause(auth, body)
                Action.STOP -> api.scrobbleStop(auth, body)
            }
        } catch (_: Exception) {
            // Best-effort: a failed scrobble never touches playback.
        }
    }

    /** Build a scrobble body from a history key. Only IMDb-keyed titles (movies
     *  "tt123" and episodes "tt123:season:episode") map to SimKL; Library items
     *  keyed by AllDebrid magnet id are skipped. */
    private fun scrobbleBody(entry: HistoryEntry, progress: Double): SimklScrobbleReq? {
        if (!entry.key.startsWith("tt")) return null
        val parts = entry.key.split(":")
        return if (parts.size >= 3) {
            val season = parts[1].toIntOrNull() ?: return null
            val number = parts[2].toIntOrNull() ?: return null
            SimklScrobbleReq(
                progress = progress,
                show = SimklShow(ids = SimklIds(imdb = parts[0])),
                episode = SimklEpisode(season = season, number = number)
            )
        } else {
            SimklScrobbleReq(progress = progress, movie = SimklMovie(ids = SimklIds(imdb = parts[0])))
        }
    }

    // ---- Sync playback (pull) ----------------------------------------------

    /**
     * Fetch SimKL resume points, skipping if the last pull was under [minIntervalMs]
     * ago. The caller sets the cadence: Home passes a short interval so walking up to
     * a TV that's been sitting on the screen refreshes your position quickly; a long
     * default guards any accidental background caller. SimKL only objects to
     * *unconditional background* polling — a foreground screen the user is looking at
     * refreshing every minute or two is fine and nowhere near the daily request cap.
     */
    fun firePull(minIntervalMs: Long = DEFAULT_THROTTLE_MS) {
        scope.launch { pullPlayback(minIntervalMs) }
    }

    /** Merge SimKL's resume points into local history — but only entries newer
     *  than what this device already has, so a spot you just set locally isn't
     *  clobbered by a stale remote one. */
    suspend fun pullPlayback(minIntervalMs: Long = DEFAULT_THROTTLE_MS) {
        if (!isConfigured() || !settings.simklEnabledOrDefault()) return
        val token = validToken() ?: return
        // Throttle purely on wall-clock so rapid re-triggers (bouncing in/out of Home)
        // don't spam the API, while a genuine periodic/foreground refresh gets through.
        val now = System.currentTimeMillis()
        if (now - settings.simklLastPullAt() < minIntervalMs) return
        settings.setSimklLastPullAt(now)
        val items = try {
            withContext(Dispatchers.IO) { api.playback(bearer(token)) }
        } catch (_: Exception) {
            return
        }
        for (item in items) {
            if (item.progress <= 0.0) continue
            val pausedAt = parseIso(item.paused_at) ?: continue
            val mapped = mapItem(item) ?: continue
            val existing = history.get(mapped.key)
            if (existing != null && existing.updatedAt >= pausedAt) continue
            // Turn SimKL's percent into an absolute resume position. Prefer the runtime
            // SimKL reports; if it's missing, reuse a duration this device already knows
            // for the title (e.g. an earlier local watch) so the spot still carries,
            // rather than collapsing progress to 0.
            val durationMs = ((mapped.runtimeMin ?: 0) * 60_000L)
                .takeIf { it > 0 } ?: (existing?.durationMs ?: 0L)
            val positionMs = if (durationMs > 0) (item.progress / 100.0 * durationMs).toLong() else 0L
            val poster = existing?.poster
                ?: runCatching { posterFor(mapped.type, mapped.showImdb) }.getOrNull()
            history.upsert(
                HistoryEntry(
                    key = mapped.key,
                    type = mapped.type,
                    title = mapped.title,
                    poster = poster,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = pausedAt
                )
            )
        }
    }

    private data class Mapped(
        val key: String,
        val type: String,
        val showImdb: String,
        val title: String,
        val runtimeMin: Int?
    )

    private fun mapItem(item: SimklPlaybackItem): Mapped? = when (item.type) {
        "episode" -> {
            val imdb = item.show?.ids?.imdb
            val season = item.episode?.season
            val number = item.episode?.number
            if (imdb == null || season == null || number == null) null
            else Mapped(
                key = "$imdb:$season:$number",
                type = "series",
                showImdb = imdb,
                title = item.show.title ?: imdb,
                runtimeMin = item.episode.runtime
            )
        }
        "movie" -> {
            val imdb = item.movie?.ids?.imdb
            if (imdb == null) null
            else Mapped(
                key = imdb,
                type = "movie",
                showImdb = imdb,
                title = item.movie.title ?: imdb,
                runtimeMin = item.movie.runtime
            )
        }
        else -> null
    }

    /** Parse a SimKL ISO-8601 UTC timestamp (e.g. 2024-04-30T22:13:00Z) to epoch
     *  millis. SimpleDateFormat instead of java.time to stay on minSdk 24. */
    private fun parseIso(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        for (pattern in ISO_PATTERNS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(raw)?.time
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        return null
    }

    private companion object {
        // Fallback throttle for any caller that doesn't specify one. Home overrides
        // this with a short interval (see FOREGROUND_PULL_MIN_INTERVAL_MS there).
        const val DEFAULT_THROTTLE_MS = 15 * 60 * 1000L // 15 min

        val ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
    }
}
