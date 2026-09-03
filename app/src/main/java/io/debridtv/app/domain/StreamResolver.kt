package io.debridtv.app.domain

import io.debridtv.app.data.alldebrid.AllDebridClient
import io.debridtv.app.data.alldebrid.AllDebridException
import io.debridtv.app.data.alldebrid.FileNode
import io.debridtv.app.data.alldebrid.MagnetInfo
import io.debridtv.app.data.net.Net
import kotlinx.coroutines.delay

/**
 * Turns a scraped [StreamSource] into a directly playable URL:
 *   magnet -> AllDebrid (cache/download) -> pick the right file -> unlock.
 */
class StreamResolver(private val ad: AllDebridClient) {

    suspend fun resolve(
        source: StreamSource,
        episodeHint: Pair<Int, Int>? = null,
        onProgress: (String) -> Unit = {}
    ): ResolvedStream {
        onProgress("Adding to AllDebrid…")
        // Bound the pre-player wait. A source already cached on AllDebrid reports Ready
        // in ~1 poll, so this cap never bites for the common case; it only stops a
        // NOT-cached or dead magnet (no seeders) from holding the resolve spinner for the
        // full 180s download window. On timeout the caller surfaces a message and the user
        // can pick another source (or Queue this one to cache in the background).
        val info = ad.ensureReady(source.magnet(), timeoutMs = READY_TIMEOUT_MS) {
            onProgress(progressText(it))
        }
        val id = info.id ?: throw AllDebridException("AllDebrid returned no magnet id")

        val files = ad.files(id)
        val file = pickFile(files, source.fileIdx, episodeHint)
            ?: throw AllDebridException("No playable file found in this source")

        onProgress("Unlocking stream…")
        val url = ad.unlock(file.l!!)
        waitUntilServable(url, onProgress)
        return ResolvedStream(url = url, filename = file.n ?: source.filename, magnetId = id)
    }

    /**
     * A magnet reports Ready the instant its download finishes, but the CDN often can't
     * stream it for a few more seconds — handing the link to the player too early causes a
     * load-stall-load cycle. Probe the actual link and return the MOMENT it serves bytes,
     * waiting only as long as genuinely needed (capped). An already-servable link (cached
     * source, Library, re-unlock retry) clears the first probe in ~1 request, so there's no
     * fixed penalty. If it never becomes servable within the cap we return anyway and let the
     * player's retry/fallback handle it.
     */
    private suspend fun waitUntilServable(url: String, onProgress: (String) -> Unit) {
        var waited = 0L
        while (waited < PROBE_MAX_WAIT_MS) {
            if (Net.isStreamServable(url)) return
            onProgress("Preparing stream…")
            delay(PROBE_INTERVAL_MS)
            waited += PROBE_INTERVAL_MS
        }
    }

    /** Kick off caching without waiting for it to finish (auto-queue for later). */
    suspend fun preload(source: StreamSource) {
        ad.uploadMagnet(source.magnet())
    }

    /** Play a magnet that is already on the AllDebrid account (Library screen). */
    suspend fun resolveReadyMagnet(info: MagnetInfo): ResolvedStream {
        val id = info.id ?: throw AllDebridException("Magnet has no id")
        val file = pickFile(ad.files(id), null, null)
            ?: throw AllDebridException("No playable video file in this item")
        val url = ad.unlock(file.l!!)
        return ResolvedStream(url = url, filename = file.n ?: info.filename ?: "video", magnetId = id)
    }

    private fun progressText(info: MagnetInfo): String = when {
        info.isReady -> "Ready on AllDebrid"
        info.isError -> "AllDebrid error: ${info.status ?: "unknown"}"
        (info.statusCode ?: 0) == 1 ->
            "Caching on AllDebrid… ${info.progressPercent}%" +
                (info.seeders?.let { " ($it seeders)" } ?: "")
        else -> "AllDebrid: ${info.status ?: "queued"}…"
    }

    private fun pickFile(
        files: List<FileNode>,
        fileIdx: Int?,
        episodeHint: Pair<Int, Int>?
    ): FileNode? {
        val valid = files.filter { !it.l.isNullOrBlank() }
        if (valid.isEmpty()) return null

        val videos = valid.filter { isVideo(it.n) }.ifEmpty { valid }

        // 1) Episode packs: match SxxExx in the filename.
        episodeHint?.let { (season, episode) ->
            val patterns = listOf(
                Regex("s0*%d\\s*e0*%d".format(season, episode), RegexOption.IGNORE_CASE),
                Regex("%dx0*%d".format(season, episode), RegexOption.IGNORE_CASE)
            )
            videos.firstOrNull { link -> patterns.any { it.containsMatchIn(link.n ?: "") } }
                ?.let { return it }
        }

        // 2) Honour the scraper's file index when there's a clean 1:1 mapping.
        if (fileIdx != null && videos.size == valid.size && fileIdx in valid.indices) {
            val candidate = valid[fileIdx]
            if (isVideo(candidate.n)) return candidate
        }

        // 3) Largest video file.
        return videos.maxByOrNull { it.s ?: 0 }
    }

    private fun isVideo(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return VIDEO_EXT.any { n.endsWith(it) }
    }

    private companion object {
        val VIDEO_EXT = listOf(".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm", ".wmv", ".flv")

        // How long to wait for a just-cached link to become streamable, and how often to
        // re-probe. Returns as soon as it's servable, so these are only an upper bound —
        // a warm/cached link clears the first probe instantly. On timeout resolve hands
        // the link to the player anyway (which keeps buffering + retrying), so a shorter
        // cap just means dropping into the player sooner rather than waiting on the spinner.
        const val PROBE_MAX_WAIT_MS = 10_000L
        const val PROBE_INTERVAL_MS = 2_000L

        // Upper bound on the "waiting for AllDebrid to cache this source" step for the
        // interactive Play path. Cached sources return well inside this; an uncached/dead
        // magnet gives up here instead of polling for the full ensureReady() default.
        const val READY_TIMEOUT_MS = 25_000L
    }
}
