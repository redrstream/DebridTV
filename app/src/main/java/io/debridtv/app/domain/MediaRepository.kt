package io.debridtv.app.domain

import io.debridtv.app.data.cinemeta.CinemetaApi
import io.debridtv.app.data.cinemeta.Meta
import io.debridtv.app.data.scraper.SourceProvider
import io.debridtv.app.data.scraper.SourceRequest
import io.debridtv.app.data.subtitles.OpenSubtitlesApi
import io.debridtv.app.data.subtitles.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Facade over metadata (Cinemeta) and source scraping (pluggable providers). */
class MediaRepository(
    private val cinemeta: CinemetaApi,
    private val providers: List<SourceProvider>,
    private val openSubtitles: OpenSubtitlesApi
) {
    suspend fun popular(type: String): List<Meta> = withContext(Dispatchers.IO) {
        runCatching { cinemeta.popular(type).metas }.getOrDefault(emptyList())
    }

    /** Popular titles of a given genre (e.g. "Action"). Empty on any failure. */
    suspend fun byGenre(type: String, genre: String): List<Meta> = withContext(Dispatchers.IO) {
        runCatching { cinemeta.byGenre(type, genre).metas }.getOrDefault(emptyList())
    }

    /**
     * Searches movies + series in parallel. Throws only when BOTH calls fail
     * (i.e. a real network error) so the UI can tell "no matches" apart from
     * "couldn't reach the server"; a partial failure still returns what it got.
     */
    suspend fun search(query: String): List<Meta> = coroutineScope {
        val movies = async(Dispatchers.IO) { runCatching { cinemeta.search("movie", query).metas } }
        val series = async(Dispatchers.IO) { runCatching { cinemeta.search("series", query).metas } }
        val m = movies.await()
        val s = series.await()
        if (m.isFailure && s.isFailure) {
            throw m.exceptionOrNull() ?: s.exceptionOrNull() ?: IllegalStateException("Search failed")
        }
        m.getOrDefault(emptyList()) + s.getOrDefault(emptyList())
    }

    suspend fun meta(type: String, id: String): Meta? = withContext(Dispatchers.IO) {
        runCatching { cinemeta.meta(type, id).meta }.getOrNull()
    }

    /**
     * Runs every provider concurrently, merges the results, de-dupes by
     * infoHash (keeping the best-seeded copy) and ranks by quality then seeders.
     * A provider that throws contributes nothing rather than failing the whole
     * search.
     */
    suspend fun sources(request: SourceRequest): List<StreamSource> = coroutineScope {
        val perProvider = providers.map { provider ->
            async(Dispatchers.IO) {
                runCatching { provider.fetch(request) }.getOrDefault(emptyList())
            }
        }.awaitAll()

        perProvider.flatten()
            .filter { it.infoHash.length == 40 || it.infoHash.length == 32 }
            .groupBy { it.infoHash }
            .map { (_, group) -> group.maxByOrNull { it.seeders ?: 0 } ?: group.first() }
            .sortedWith(
                compareByDescending<StreamSource> { it.qualityRank }
                    .thenByDescending { it.seeders ?: 0 }
            )
            .take(MAX_SOURCES)
    }

    /** Subtitle tracks for a movie/episode (id = "tt123" or "tt123:1:2"). */
    suspend fun subtitles(type: String, id: String): List<SubtitleTrack> =
        withContext(Dispatchers.IO) {
            runCatching { openSubtitles.get(type, id).subtitles }
                .getOrDefault(emptyList())
                .filter { it.url.isNotBlank() }
                .take(MAX_SUBTITLES)
        }

    private companion object {
        const val MAX_SOURCES = 100
        const val MAX_SUBTITLES = 40
    }
}
