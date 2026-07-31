package io.debridtv.app.data.cinemeta

import io.debridtv.app.data.net.Net
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Cinemeta is the same keyless metadata catalog that Stremio uses. It is
 * indexed by IMDb id (tt…), which is exactly what Torrentio expects, so no
 * TMDB key or id-mapping is needed.
 */
interface CinemetaApi {
    @GET("catalog/{type}/top.json")
    suspend fun popular(@Path("type") type: String): CatalogResponse

    /** Same "top" catalog filtered by a genre (e.g. Action, Comedy, Sci-Fi). */
    @GET("catalog/{type}/top/genre={genre}.json")
    suspend fun byGenre(
        @Path("type") type: String,
        @Path("genre") genre: String
    ): CatalogResponse

    @GET("catalog/{type}/top/search={query}.json")
    suspend fun search(
        @Path("type") type: String,
        @Path("query") query: String
    ): CatalogResponse

    @GET("meta/{type}/{id}.json")
    suspend fun meta(
        @Path("type") type: String,
        @Path(value = "id", encoded = true) id: String
    ): MetaResponse
}

@Serializable
data class CatalogResponse(val metas: List<Meta> = emptyList())

@Serializable
data class MetaResponse(val meta: Meta? = null)

@Serializable
data class Meta(
    val id: String,
    val type: String? = null,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<Video> = emptyList()
)

@Serializable
data class Video(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null
) {
    val episodeNumber: Int? get() = episode ?: number
    val displayLabel: String
        get() = buildString {
            if (season != null && episodeNumber != null) append("S%02dE%02d  ".format(season, episodeNumber))
            append(name ?: title ?: "Episode")
        }
}

object CinemetaService {
    private const val BASE = "https://v3-cinemeta.strem.io/"
    // Cached client: metadata/catalogs are cached on disk (see Net.maxAgeSeconds).
    val api: CinemetaApi by lazy { Net.retrofitCached(BASE).create(CinemetaApi::class.java) }
}
