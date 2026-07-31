package io.debridtv.app.domain

import io.debridtv.app.data.alldebrid.AllDebridClient
import io.debridtv.app.data.alldebrid.AllDebridException
import io.debridtv.app.data.alldebrid.FileNode
import io.debridtv.app.data.alldebrid.MagnetInfo

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
        val info = ad.ensureReady(source.magnet()) { onProgress(progressText(it)) }
        val id = info.id ?: throw AllDebridException("AllDebrid returned no magnet id")

        val files = ad.files(id)
        val file = pickFile(files, source.fileIdx, episodeHint)
            ?: throw AllDebridException("No playable file found in this source")

        onProgress("Unlocking stream…")
        val url = ad.unlock(file.l!!)
        return ResolvedStream(url = url, filename = file.n ?: source.filename, magnetId = id)
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
    }
}
