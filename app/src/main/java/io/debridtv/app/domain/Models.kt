package io.debridtv.app.domain

import io.debridtv.app.data.torrentio.TorrentioStream
import java.net.URLEncoder

/** A single playable candidate parsed from a Torrentio result. */
data class StreamSource(
    val infoHash: String,
    val fileIdx: Int?,
    val filename: String,
    val quality: String,
    val sizeText: String?,
    val seeders: Int?,
    val provider: String?,
    val rawTitle: String
) {
    /** Sort key: prefer higher resolution, then more seeders. */
    val qualityRank: Int
        get() = when {
            quality.contains("2160") || quality.contains("4K", true) -> 4
            quality.contains("1080") -> 3
            quality.contains("720") -> 2
            quality.contains("480") -> 1
            else -> 0
        }

    fun magnet(): String {
        val trackers = TRACKERS.joinToString("") { "&tr=" + enc(it) }
        return "magnet:?xt=urn:btih:$infoHash&dn=${enc(filename)}$trackers"
    }

    companion object {
        private val RES = Regex("(2160p|1080p|720p|480p|4k)", RegexOption.IGNORE_CASE)
        private val SIZE = Regex("💾\\s*([0-9.]+\\s*[GMK]B)", RegexOption.IGNORE_CASE)
        private val SEEDS = Regex("👤\\s*([0-9]+)")
        private val PROVIDER = Regex("⚙️\\s*([^\\n]+)")

        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce"
        )

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Best-effort resolution label from a torrent/file name. */
        fun qualityFrom(text: String): String = RES.find(text)?.value?.uppercase() ?: "SD"

        fun from(s: TorrentioStream): StreamSource? {
            val hash = s.infoHash?.trim()?.lowercase() ?: return null
            val title = s.title ?: s.name ?: ""
            val firstLine = title.lineSequence().firstOrNull()?.trim().orEmpty()
            val quality = RES.find(s.name ?: title)?.value?.uppercase() ?: "SD"
            val size = SIZE.find(title)?.groupValues?.get(1)?.trim()
            val seeds = SEEDS.find(title)?.groupValues?.get(1)?.toIntOrNull()
            val provider = PROVIDER.find(title)?.groupValues?.get(1)?.trim()
            return StreamSource(
                infoHash = hash,
                fileIdx = s.fileIdx,
                filename = firstLine.ifBlank { s.name ?: hash },
                quality = quality,
                sizeText = size,
                seeders = seeds,
                provider = provider,
                rawTitle = title
            )
        }
    }
}

/** Result of resolving a source through AllDebrid: a direct URL ExoPlayer can play. */
data class ResolvedStream(
    val url: String,
    val filename: String,
    val magnetId: Long?
)
