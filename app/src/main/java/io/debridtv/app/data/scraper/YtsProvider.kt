package io.debridtv.app.data.scraper

import io.debridtv.app.domain.StreamSource
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * YTS — high-quality movie releases (keyless JSON API). Movies only; queried by
 * IMDb code. Torrents are single-file, so no fileIdx is needed.
 */
interface YtsApi {
    @GET("api/v2/list_movies.json")
    suspend fun list(
        @Query("query_term") query: String,
        @Query("limit") limit: Int = 50
    ): YtsResponse
}

@Serializable
data class YtsResponse(val data: YtsData? = null)

@Serializable
data class YtsData(val movies: List<YtsMovie> = emptyList())

@Serializable
data class YtsMovie(
    val title: String = "",
    val year: Int? = null,
    val torrents: List<YtsTorrent> = emptyList()
)

@Serializable
data class YtsTorrent(
    val hash: String = "",
    val quality: String = "",
    val type: String = "",
    val size: String = "",
    val seeds: Int = 0
)

class YtsProvider(private val api: YtsApi) : SourceProvider {
    override val name = "YTS"

    override suspend fun fetch(request: SourceRequest): List<StreamSource> {
        if (request.isSeries) return emptyList()
        val movies = api.list(request.imdbId).data?.movies.orEmpty()
        return movies.flatMap { movie ->
            movie.torrents.mapNotNull { t ->
                if (t.hash.isBlank()) return@mapNotNull null
                StreamSource(
                    infoHash = t.hash.lowercase(),
                    fileIdx = null,
                    filename = listOf(movie.title, t.quality, t.type)
                        .filter { it.isNotBlank() }.joinToString(" "),
                    quality = t.quality.uppercase().ifBlank { "SD" },
                    sizeText = t.size.ifBlank { null },
                    seeders = t.seeds,
                    provider = "YTS",
                    rawTitle = "${movie.title} ${t.quality} ${t.type}"
                )
            }
        }
    }
}
