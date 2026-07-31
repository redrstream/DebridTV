package io.debridtv.app.data.scraper

import io.debridtv.app.domain.StreamSource

/** What we want sources for. */
data class SourceRequest(
    val type: String,          // "movie" | "series"
    val imdbId: String,        // tt1234567
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val year: Int? = null
) {
    val isSeries: Boolean get() = type == "series"

    /** Stremio-style id used by Torrentio (tt123 or tt123:1:2). */
    val stremioId: String
        get() = if (isSeries && season != null && episode != null) "$imdbId:$season:$episode" else imdbId

    /** IMDb number only, no "tt" and no leading zeros (some indexers require this). */
    val numericImdb: String get() = imdbId.removePrefix("tt").trimStart('0')

    /** A free-text query for indexers that don't take an IMDb id. */
    fun queryString(): String? {
        val t = title?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        return when {
            isSeries && season != null && episode != null ->
                "%s S%02dE%02d".format(t, season, episode)
            year != null -> "$t $year"
            else -> t
        }
    }
}

/**
 * A single source of torrents. Implementations are independent and failure of
 * one is isolated by [io.debridtv.app.domain.MediaRepository]. Adding a new
 * indexer = one new class registered in the ServiceLocator provider list.
 */
interface SourceProvider {
    val name: String
    suspend fun fetch(request: SourceRequest): List<StreamSource>
}

/** Human-readable size from a byte count, e.g. 2469606195 -> "2.3 GB". */
internal fun formatBytes(bytes: Long?): String? {
    val b = bytes ?: return null
    if (b <= 0) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = b.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return "%.1f %s".format(value, units[i])
}
