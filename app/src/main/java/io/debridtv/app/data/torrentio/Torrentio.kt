package io.debridtv.app.data.torrentio

import io.debridtv.app.data.net.Net
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Torrentio (same public scraper Stremio users configure) returns torrent
 * sources for a given IMDb id. We query it WITHOUT a debrid config so it
 * hands back raw infoHashes, then we resolve those ourselves through
 * AllDebrid — that keeps caching / auto-queue logic in our control.
 *
 * Series ids use the form tt1234567:1:2 (imdb:season:episode); the colons
 * must stay literal, hence encoded = true on the path.
 */
interface TorrentioApi {
    @GET("stream/{type}/{id}.json")
    suspend fun streams(
        @Path("type") type: String,
        @Path(value = "id", encoded = true) id: String
    ): TorrentioResponse
}

@Serializable
data class TorrentioResponse(val streams: List<TorrentioStream> = emptyList())

@Serializable
data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val sources: List<String> = emptyList()
)

object TorrentioService {
    private const val BASE = "https://torrentio.strem.fun/"
    val api: TorrentioApi by lazy { Net.retrofit(BASE).create(TorrentioApi::class.java) }
}
