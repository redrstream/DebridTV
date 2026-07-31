package io.debridtv.app.data.subtitles

import io.debridtv.app.data.net.Net
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * OpenSubtitles v3 — the same keyless Stremio subtitles addon. Returns direct
 * subtitle file URLs (usually .srt) keyed by IMDb id (tt123 or tt123:1:2).
 */
interface OpenSubtitlesApi {
    @GET("subtitles/{type}/{id}.json")
    suspend fun get(
        @Path("type") type: String,
        @Path(value = "id", encoded = true) id: String
    ): SubtitlesResponse
}

@Serializable
data class SubtitlesResponse(val subtitles: List<SubtitleTrack> = emptyList())

@Serializable
data class SubtitleTrack(
    val id: String = "",
    val url: String = "",
    val lang: String = ""
)

object OpenSubtitlesService {
    private const val BASE = "https://opensubtitles-v3.strem.io/"
    val api: OpenSubtitlesApi by lazy { Net.retrofit(BASE).create(OpenSubtitlesApi::class.java) }
}
