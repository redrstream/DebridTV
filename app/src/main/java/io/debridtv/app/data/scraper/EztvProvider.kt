package io.debridtv.app.data.scraper

import io.debridtv.app.domain.StreamSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * EZTV — TV episodes (keyless JSON API), queried by numeric IMDb id. Series only.
 * The public API host changes occasionally; update BASE in EztvService if it moves.
 */
interface EztvApi {
    @GET("api/get-torrents")
    suspend fun get(
        @Query("imdb_id") imdbId: String,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 1
    ): EztvResponse
}

@Serializable
data class EztvResponse(val torrents: List<EztvTorrent> = emptyList())

@Serializable
data class EztvTorrent(
    val title: String = "",
    val hash: String = "",
    @SerialName("magnet_url") val magnetUrl: String = "",
    val filename: String? = null,
    val seeds: Int = 0,
    val season: String? = null,
    val episode: String? = null,
    @SerialName("size_bytes") val sizeBytes: String? = null
)

class EztvProvider(private val api: EztvApi) : SourceProvider {
    override val name = "EZTV"

    override suspend fun fetch(request: SourceRequest): List<StreamSource> {
        if (!request.isSeries) return emptyList()
        val imdb = request.numericImdb.ifBlank { return emptyList() }
        val torrents = api.get(imdb).torrents

        val filtered = if (request.season != null && request.episode != null) {
            torrents.filter {
                it.season?.toIntOrNull() == request.season &&
                    it.episode?.toIntOrNull() == request.episode
            }
        } else torrents

        return filtered.mapNotNull { t ->
            if (t.hash.isBlank()) return@mapNotNull null
            StreamSource(
                infoHash = t.hash.lowercase(),
                fileIdx = null,
                filename = t.filename ?: t.title,
                quality = StreamSource.qualityFrom(t.title),
                sizeText = formatBytes(t.sizeBytes?.toLongOrNull()),
                seeders = t.seeds,
                provider = "EZTV",
                rawTitle = t.title
            )
        }
    }
}
