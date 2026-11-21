package io.debridtv.app.data.simkl

import io.debridtv.app.BuildConfig
import io.debridtv.app.data.prefs.HistoryEntry
import io.debridtv.app.data.prefs.HistoryStore
import io.debridtv.app.data.prefs.SettingsStore
import io.debridtv.app.data.prefs.SimklSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Poster + runtime for a title, looked up (Cinemeta-backed) for items first seen on
 *  another device: the poster stops the Continue Watching card being a blank
 *  placeholder, and the runtime turns SimKL's progress percent into a resume position
 *  (the playback endpoint returns a percent, not a duration). */
data class SimklTitleInfo(val poster: String?, val runtimeMin: Int?)

/**
 * Cross-device watch sync via SimKL. A *mirror* layer: the local [HistoryStore]
 * stays the single source of truth for the player, and network calls here never
 * block or break playback.
 *
 * - Push: scrobble start / pause / stop as you watch (pause + stop carry the
 *   position; stop at >=80% marks watched), plus explicit history marks for the
 *   manual "Mark watched" button.
 * - Pull ([syncNow]): the SimKL-recommended loop — read the cheap /sync/activities
 *   timestamps, and only if something moved, fetch the watched-history delta
 *   (/sync/all-items, extended=full) and/or the resume points (/sync/playback), then
 *   merge the newer ones into local history.
 *
 * Every outcome (success or the exact error) is recorded in [SettingsStore.simklStatus]
 * so Settings can show it, and a 401 raises the "reconnect SimKL" warning on Home.
 */
class SimklClient(
    private val api: SimklApi,
    private val settings: SettingsStore,
    private val history: HistoryStore,
    private val titleInfoFor: suspend (type: String, imdb: String) -> SimklTitleInfo?
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
            // Pull everything for the new account straight away.
            syncNow(minIntervalMs = 0L, force = true)
            PollResult.Success
        } else {
            PollResult.Pending
        }
    }

    suspend fun disconnect() = settings.clearSimkl()

    private fun bearer(token: String) = "Bearer $token"

    private suspend fun validToken(): String? =
        settings.simklTokenOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun active(): Boolean = isConfigured() && settings.simklEnabledOrDefault()

    // ---- Status reporting --------------------------------------------------

    /** Plain-language description of a failed call, for the Settings status line. */
    private fun describe(code: Int): String = when (code) {
        401 -> "SimKL sign-in expired or was removed — reconnect (401)"
        403 -> "SimKL refused access (403)"
        412 -> "SimKL rejected this app's key (412)"
        429 -> "SimKL rate limit — will retry (429)"
        in 500..599 -> "SimKL server error ($code)"
        else -> "SimKL error ($code)"
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpException -> describe(e.code())
        is IOException -> "No connection to SimKL"
        else -> "SimKL error (${e.javaClass.simpleName})"
    }

    private suspend fun recordSync(ok: Boolean, msg: String, httpCode: Int? = null) {
        settings.updateSimklStatus {
            it.copy(
                lastSyncAt = System.currentTimeMillis(), lastSyncOk = ok, lastSyncMsg = msg,
                needsReconnect = if (httpCode == 401) true else if (ok) false else it.needsReconnect
            )
        }
    }

    private suspend fun recordPush(ok: Boolean, msg: String, httpCode: Int? = null) {
        settings.updateSimklStatus {
            it.copy(
                lastPushAt = System.currentTimeMillis(), lastPushOk = ok, lastPushMsg = msg,
                needsReconnect = if (httpCode == 401) true else if (ok) false else it.needsReconnect
            )
        }
    }

    // ---- Scrobble (push) ---------------------------------------------------

    fun fireStart(entry: HistoryEntry, progress: Double) = fire(entry, progress, Action.START)
    fun firePause(entry: HistoryEntry, progress: Double) = fire(entry, progress, Action.PAUSE)
    fun fireStop(entry: HistoryEntry, progress: Double) = fire(entry, progress, Action.STOP)

    private enum class Action(val label: String) { START("start"), PAUSE("pause"), STOP("stop") }

    // Pushes go out one at a time, in the order they happened (Mutex is FIFO). SimKL
    // holds a 20-second per-user lock on scrobbles and rejects an overlapping call, so
    // firing STOP (old episode) and START (next episode) concurrently could lose one.
    private val pushMutex = Mutex()

    private fun fire(entry: HistoryEntry, progress: Double, action: Action) {
        scope.launch { pushMutex.withLock { scrobble(entry, progress, action) } }
    }

    private suspend fun scrobble(entry: HistoryEntry, progress: Double, action: Action) {
        if (!active()) return
        val body = scrobbleBody(entry, progress.coerceIn(0.0, 100.0)) ?: return
        val token = validToken() ?: return
        val auth = bearer(token)
        sendWithLockRetry("${action.label} ${label(entry.key)}") {
            when (action) {
                Action.START -> api.scrobbleStart(auth, body)
                Action.PAUSE -> api.scrobblePause(auth, body)
                Action.STOP -> api.scrobbleStop(auth, body)
            }
        }
    }

    /**
     * Send a push, retrying ONCE after the 20s lock window if SimKL rejected it as an
     * overlapping call (400 RATE_LIMIT / 429). 409 on stop = already recorded as
     * watched within the hour — that's success, not an error. Records the outcome.
     */
    private suspend fun sendWithLockRetry(what: String, call: suspend () -> Response<*>) {
        repeat(2) { attempt ->
            val code = try {
                call().code()
            } catch (e: Exception) {
                recordPush(false, "Couldn't send $what: ${describe(e)}")
                return
            }
            when {
                code in 200..299 || code == 409 -> {
                    recordPush(true, "Sent $what")
                    return
                }
                (code == 400 || code == 429) && attempt == 0 -> delay(LOCK_RETRY_MS)
                else -> {
                    recordPush(false, "Couldn't send $what: ${describe(code)}", code)
                    return
                }
            }
        }
    }

    private fun label(key: String): String {
        val p = key.split(":")
        return if (p.size >= 3) "S${p[1]}E${p[2]}" else "movie"
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

    // ---- Explicit watched marks (push) -------------------------------------

    /** Push a manual "Mark watched" / "Mark unwatched" from the TV to SimKL so the
     *  other TVs see it too. Scrobbles only cover things actually played, so a manual
     *  mark needs the history endpoint. */
    fun fireSetWatched(key: String, watched: Boolean) {
        scope.launch {
            pushMutex.withLock {
                if (!active()) return@withLock
                val body = historyBody(key) ?: return@withLock
                val token = validToken() ?: return@withLock
                val what = "${if (watched) "watched" else "unwatched"} mark ${label(key)}"
                sendWithLockRetry(what) {
                    if (watched) api.addToHistory(bearer(token), body)
                    else api.removeFromHistory(bearer(token), body)
                }
            }
        }
    }

    // ---- Continue-Watching removal (push) ----------------------------------

    /**
     * The user removed a title from Continue Watching on this TV. SimKL has no
     * "hide from Continue Watching" flag, so (per the user's choice) the title is moved
     * to the "dropped" list and its saved resume points are deleted. Watched history is
     * kept. Other TVs see the status change on their next sync and hide it too
     * ([applyWatched]). [bareId] = "tt123"; [isSeries] picks shows vs movies.
     */
    fun fireDismiss(bareId: String, isSeries: Boolean) {
        if (!bareId.startsWith("tt")) return
        scope.launch {
            pushMutex.withLock {
                if (!active()) return@withLock
                val token = validToken() ?: return@withLock
                val auth = bearer(token)
                val item = SimklListItem(to = "dropped", ids = SimklIds(imdb = bareId))
                val body = if (isSeries) SimklListReq(shows = listOf(item)) else SimklListReq(movies = listOf(item))
                sendWithLockRetry("removal (dropped)") { api.addToList(auth, body) }
                // Clear any saved resume points for it so nothing drags it back.
                val sessions = try {
                    withContext(Dispatchers.IO) { api.playbackAll(auth) }
                } catch (_: Exception) {
                    return@withLock
                }
                sessions
                    .filter { s -> (s.show ?: s.anime)?.ids?.imdb == bareId || s.movie?.ids?.imdb == bareId }
                    .mapNotNull { it.id }
                    .forEach { id -> runCatching { api.deletePlayback(auth, id) } }
            }
        }
    }

    private fun historyBody(key: String): SimklHistoryReq? {
        if (!key.startsWith("tt")) return null
        val parts = key.split(":")
        return if (parts.size >= 3) {
            val season = parts[1].toIntOrNull() ?: return null
            val number = parts[2].toIntOrNull() ?: return null
            SimklHistoryReq(
                shows = listOf(
                    SimklHistoryShow(
                        ids = SimklIds(imdb = parts[0]),
                        seasons = listOf(SimklHistorySeason(season, listOf(SimklHistoryEpisode(number))))
                    )
                )
            )
        } else {
            SimklHistoryReq(movies = listOf(SimklHistoryMovie(ids = SimklIds(imdb = parts[0]))))
        }
    }

    // ---- Sync (pull) -------------------------------------------------------

    private val syncMutex = Mutex()

    private enum class SyncResult { DONE, RETRY, FAILED }

    /**
     * Bring this TV up to date with the other TVs: watched/finished state AND resume
     * points. Called whenever the app comes to the foreground (any screen), from Home's
     * slow backstop, and from Settings → Sync now ([force] = re-pull the last few days
     * regardless of what activities says).
     *
     * A TV waking from standby often has no network for the first few seconds, so a
     * connection failure is retried a couple of times. If a sync is already running,
     * this call is a no-op.
     */
    fun syncNow(minIntervalMs: Long = DEFAULT_THROTTLE_MS, force: Boolean = false) {
        scope.launch {
            if (!syncMutex.tryLock()) return@launch
            try {
                for (wait in RETRY_DELAYS_MS) {
                    if (wait > 0) delay(wait)
                    if (runSync(minIntervalMs, force) != SyncResult.RETRY) break
                }
            } finally {
                syncMutex.unlock()
            }
        }
    }

    private suspend fun runSync(minIntervalMs: Long, force: Boolean): SyncResult {
        if (!active()) return SyncResult.DONE
        val token = validToken() ?: return SyncResult.DONE
        val auth = bearer(token)
        val now = System.currentTimeMillis()
        if (!force && now - settings.simklLastPullAt() < minIntervalMs) return SyncResult.DONE

        // 1. What changed? (cheap; SimKL requires this before any list pull)
        val acts = try {
            withContext(Dispatchers.IO) { api.activities(auth) }
        } catch (e: Exception) {
            recordSync(false, describe(e), (e as? HttpException)?.code())
            return if (e is IOException) SyncResult.RETRY else SyncResult.FAILED
        }
        val snap = settings.simklSnapshot()
        val all = acts.all.orEmpty()
        val watchKey = listOf(acts.tv_shows, acts.anime, acts.movies).joinToString("|") { d ->
            listOf(d?.watching, d?.completed, d?.plantowatch, d?.hold, d?.dropped).joinToString(",")
        }
        val playKey = listOf(acts.tv_shows, acts.anime, acts.movies).joinToString("|") { it?.playback.orEmpty() }

        if (!force && snap != null && snap.all == all) {
            settings.setSimklLastPullAt(now)
            recordSync(true, "Up to date")
            return SyncResult.DONE
        }

        // date_from: the saved snapshot's `all`, verbatim (SimKL returns items changed
        // strictly after it). First sync / manual Sync now: a recent window instead, so
        // Continue Watching isn't flooded with the whole back-catalogue.
        val dateFrom = if (force || snap == null) formatIso(now - FIRST_SYNC_WINDOW_MS) else snap.all

        var watchedCount = 0
        var resumeCount = 0
        // 2. Watched history delta, only if a watchlist bucket moved.
        if (force || snap == null || snap.watchKey != watchKey) {
            val resp = try {
                withContext(Dispatchers.IO) { api.allItems(auth, dateFrom = dateFrom) }
            } catch (e: Exception) {
                recordSync(false, "Watched history: ${describe(e)}", (e as? HttpException)?.code())
                return if (e is IOException) SyncResult.RETRY else SyncResult.FAILED
            }
            // Anything changed locally before this window started is older than the
            // remote change that put the item in the delta.
            val windowStart = parseIso(dateFrom) ?: now
            watchedCount = applyWatched(resp, windowStart)
        }
        // 3. Resume points, only if playback moved. The list is small (SimKL keeps
        // paused sessions 7–90 days), so take it whole rather than a delta.
        if (force || snap == null || snap.playKey != playKey) {
            val items = try {
                withContext(Dispatchers.IO) { api.playbackAll(auth) }
            } catch (e: Exception) {
                recordSync(false, "Resume points: ${describe(e)}", (e as? HttpException)?.code())
                return if (e is IOException) SyncResult.RETRY else SyncResult.FAILED
            }
            resumeCount = applyPlayback(items)
        }
        // 4. Everything succeeded — save the snapshot read BEFORE the fetches.
        settings.setSimklSnapshot(SimklSnapshot(all, watchKey, playKey))
        settings.setSimklLastPullAt(now)
        recordSync(true, "Synced — $watchedCount watched, $resumeCount resume point(s) updated")
        return SyncResult.DONE
    }

    /** Merge SimKL's resume points into local history — only entries newer than what
     *  this device already has, so a spot you just set locally isn't clobbered by a
     *  stale remote one. Returns how many entries changed. */
    private suspend fun applyPlayback(items: List<SimklPlaybackItem>): Int {
        var changed = 0
        for (item in items) {
            if (item.progress <= 0.0) continue
            val pausedAt = parseIso(item.paused_at) ?: continue
            val mapped = mapItem(item) ?: continue
            // If the user removed this from Continue Watching here, don't let a stale
            // remote resume point drag it back. A strictly-newer remote spot (watched
            // again elsewhere) still gets through and lifts the tombstone via upsert.
            val dismissedAt = history.dismissedAt(mapped.showImdb)
            if (dismissedAt != null && pausedAt <= dismissedAt) continue
            val existing = history.get(mapped.key)
            if (existing != null && existing.updatedAt >= pausedAt) continue
            // The playback endpoint gives a progress PERCENT, not a duration. Reuse a
            // duration this device already knows, else look it up from Cinemeta. Without
            // one we can't place the resume point, so skip rather than store a 0.
            val info = if (existing?.durationMs == null || existing.durationMs <= 0L) {
                runCatching { titleInfoFor(mapped.type, mapped.showImdb) }.getOrNull()
            } else null
            val durationMs = existing?.durationMs?.takeIf { it > 0 }
                ?: ((info?.runtimeMin ?: 0) * 60_000L)
            if (durationMs <= 0L) continue
            val positionMs = (item.progress / 100.0 * durationMs).toLong()
            history.upsert(
                HistoryEntry(
                    key = mapped.key,
                    type = mapped.type,
                    title = mapped.title,
                    poster = existing?.poster ?: info?.poster,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = pausedAt
                )
            )
            changed++
        }
        return changed
    }

    /**
     * Bring finished/watched state across from other devices. For a show only the
     * FURTHEST watched episode is stored — DetailScreen infers every earlier one
     * (impliedWatchedUpTo) and Continue Watching rolls to the next. Returns how many
     * entries changed.
     */
    private suspend fun applyWatched(resp: SimklAllItems, windowStart: Long): Int {
        var changed = 0
        // Removed from Continue Watching on another TV (moved to "dropped"): hide it here
        // too — unless it's been watched on THIS TV since the window began (then the
        // local activity is newer than the removal and wins).
        suspend fun applyDropped(bareId: String, isSeries: Boolean) {
            val latest = history.latestUpdateFor(bareId) ?: return
            if (latest >= windowStart) return
            if (isSeries) history.removeShow(bareId) else history.remove(bareId)
            changed++
        }
        // A drop with a watch AFTER the window began isn't a removal — the title was
        // picked up again (possibly while still listed as dropped) — so treat it normally.
        fun watchedRecently(lastWatchedAt: String?): Boolean =
            (parseIso(lastWatchedAt) ?: 0L) >= windowStart
        for (mi in resp.movies) {
            if (mi.status == "dropped" && !watchedRecently(mi.last_watched_at)) {
                mi.movie?.ids?.imdb?.let { applyDropped(it, isSeries = false) }
                continue
            }
            // Only a completed movie is "watched"; an in-progress one arrives via playback.
            if (mi.status != null && mi.status != "completed") continue
            val imdb = mi.movie?.ids?.imdb ?: continue
            if (markWatchedLocal(
                    key = imdb, type = "movie",
                    title = mi.movie.title ?: imdb, showImdb = imdb,
                    watchedAt = parseIso(mi.last_watched_at)
                )
            ) changed++
        }
        for (si in resp.shows + resp.anime) {
            val show = si.show ?: si.anime ?: continue
            val imdb = show.ids?.imdb ?: continue
            if (si.status == "dropped" && !watchedRecently(si.last_watched_at)) {
                applyDropped(imdb, isSeries = true)
                continue
            }
            val furthest = si.seasons
                .flatMap { s ->
                    s.episodes.mapNotNull { ep ->
                        val sn = s.number ?: return@mapNotNull null
                        val en = ep.number ?: return@mapNotNull null
                        Triple(sn, en, ep.watched_at)
                    }
                }
                .filter { it.first > 0 && it.second > 0 }
                .maxWithOrNull(compareBy({ it.first }, { it.second })) ?: continue
            if (markWatchedLocal(
                    key = "$imdb:${furthest.first}:${furthest.second}", type = "series",
                    title = show.title ?: imdb, showImdb = imdb,
                    watchedAt = parseIso(furthest.third) ?: parseIso(si.last_watched_at)
                )
            ) changed++
        }
        return changed
    }

    /** Mark a local history entry watched from a remote sync, without clobbering a
     *  fresher local state (e.g. a rewatch you've since started on this TV). */
    private suspend fun markWatchedLocal(
        key: String, type: String, title: String, showImdb: String, watchedAt: Long?
    ): Boolean {
        val existing = history.get(key)
        val stamp = watchedAt ?: System.currentTimeMillis()
        // Respect a Continue-Watching dismissal unless this watch is newer than it.
        val dismissedAt = history.dismissedAt(showImdb)
        if (dismissedAt != null && stamp <= dismissedAt) return false
        // Already watched here, or something fresher happened locally — leave it.
        if (existing != null && (existing.isWatched || existing.updatedAt >= stamp)) return false
        val poster = existing?.poster
            ?: runCatching { titleInfoFor(type, showImdb) }.getOrNull()?.poster
        history.upsert(
            HistoryEntry(
                key = key,
                type = type,
                title = title,
                poster = poster,
                positionMs = existing?.positionMs ?: 0L,
                durationMs = existing?.durationMs ?: 0L,
                updatedAt = maxOf(existing?.updatedAt ?: 0L, stamp),
                watched = true
            )
        )
        return true
    }

    private data class Mapped(
        val key: String,
        val type: String,
        val showImdb: String,
        val title: String
    )

    private fun mapItem(item: SimklPlaybackItem): Mapped? {
        val show = item.show ?: item.anime
        return when {
            item.type == "movie" || (item.movie != null && show == null) -> {
                val imdb = item.movie?.ids?.imdb ?: return null
                Mapped(key = imdb, type = "movie", showImdb = imdb, title = item.movie.title ?: imdb)
            }
            show != null -> {
                val imdb = show.ids?.imdb ?: return null
                val season = item.episode?.season ?: return null
                val number = item.episode.number ?: return null
                Mapped(
                    key = "$imdb:$season:$number",
                    type = "series",
                    showImdb = imdb,
                    title = show.title ?: imdb
                )
            }
            else -> null
        }
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

    /** Format epoch-ms as a SimKL ISO-8601 UTC timestamp for the date_from query. */
    private fun formatIso(epochMs: Long): String {
        val fmt = SimpleDateFormat(ISO_PATTERNS[1], Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(java.util.Date(epochMs))
    }

    private companion object {
        // Fallback throttle for any caller that doesn't specify one.
        const val DEFAULT_THROTTLE_MS = 15 * 60 * 1000L // 15 min

        // First sync / manual "Sync now" only looks back this far, so Continue
        // Watching isn't flooded with the whole back-catalogue.
        const val FIRST_SYNC_WINDOW_MS = 3L * 24 * 60 * 60 * 1000L // 3 days

        // SimKL's per-user scrobble lock is 20s; wait just past it before the one retry.
        const val LOCK_RETRY_MS = 21_000L

        // syncNow: try right away, then retry after 5s and 15s if the network wasn't
        // up yet (typical right after a TV wakes from standby).
        val RETRY_DELAYS_MS = longArrayOf(0L, 5_000L, 15_000L)

        val ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
    }
}
