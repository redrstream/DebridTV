package io.debridtv.app.data.simkl

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * SimKL API (https://api.simkl.com). Only the slice DebridTV needs for
 * cross-device resume: the PIN device flow (log in without typing on the TV),
 * scrobble (push where you paused), and sync/playback (pull resume points onto
 * another device).
 *
 * The constant `simkl-api-key` (= the app's client id), `User-Agent` and the
 * `client_id` query param are injected by the dedicated client in
 * [Net.retrofitSimkl]; the per-user Bearer token is passed here per-call.
 * SimKL's PIN flow needs no client secret and its tokens don't expire, so there
 * is no refresh step.
 */
interface SimklApi {

    // ---- PIN device flow ---------------------------------------------------

    /** Request a PIN. client_id is added by the client interceptor. */
    @GET("oauth/pin")
    suspend fun requestPin(): SimklPinResp

    /** Poll until the user approves the PIN. Returns result="OK" + access_token
     *  on success, or result="KO" while still pending. */
    @GET("oauth/pin/{userCode}")
    suspend fun pollPin(@Path("userCode") userCode: String): SimklPinPoll

    // ---- Scrobble ----------------------------------------------------------

    @POST("scrobble/start")
    suspend fun scrobbleStart(@Header("Authorization") auth: String, @Body body: SimklScrobbleReq): Response<SimklScrobbleResp>

    @POST("scrobble/pause")
    suspend fun scrobblePause(@Header("Authorization") auth: String, @Body body: SimklScrobbleReq): Response<SimklScrobbleResp>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(@Header("Authorization") auth: String, @Body body: SimklScrobbleReq): Response<SimklScrobbleResp>

    // ---- Sync playback (resume points) -------------------------------------

    @GET("sync/playback")
    suspend fun playback(@Header("Authorization") auth: String): List<SimklPlaybackItem>

    // ---- Sync all-items (watched / finished state) -------------------------

    /**
     * The user's whole library grouped by media type, with per-episode watched
     * timestamps (episode_watched_at=yes). Used to bring finished/watched state across
     * from other devices — the resume list ([playback]) drops items once they pass
     * ~80%, so this is how a "finished elsewhere" title becomes watched here too.
     * `date_from` (ISO-8601) makes it incremental after the first full sync.
     */
    @GET("sync/all-items")
    suspend fun allItems(
        @Header("Authorization") auth: String,
        @Query("episode_watched_at") episodeWatchedAt: String = "yes",
        @Query("date_from") dateFrom: String? = null
    ): SimklAllItems
}

// ---- Shared media DTOs -----------------------------------------------------

@Serializable
data class SimklIds(
    val simkl: Long? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null
)

@Serializable
data class SimklMovie(
    val title: String? = null,
    val year: Int? = null,
    val ids: SimklIds? = null,
    val runtime: Int? = null
)

@Serializable
data class SimklShow(
    val title: String? = null,
    val year: Int? = null,
    val ids: SimklIds? = null
)

@Serializable
data class SimklEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    val runtime: Int? = null
)

// ---- PIN flow DTOs ---------------------------------------------------------

@Serializable
data class SimklPinResp(
    val user_code: String,
    val verification_url: String,
    val expires_in: Int = 900,
    val interval: Int = 5,
    val device_code: String? = null
)

@Serializable
data class SimklPinPoll(
    val result: String? = null,
    val access_token: String? = null,
    val message: String? = null
)

// ---- Scrobble / playback DTOs ---------------------------------------------

/** progress is 0–100. Send `movie` for a film, or `show` + `episode` for an
 *  episode. Nulls are dropped by the JSON encoder (explicitNulls = false). */
@Serializable
data class SimklScrobbleReq(
    val progress: Double,
    val movie: SimklMovie? = null,
    val show: SimklShow? = null,
    val episode: SimklEpisode? = null
)

@Serializable
data class SimklScrobbleResp(
    val result: String? = null,
    val progress: Double? = null
)

@Serializable
data class SimklPlaybackItem(
    val id: Long? = null,
    val progress: Double = 0.0,
    val paused_at: String? = null,
    val type: String? = null,
    val movie: SimklMovie? = null,
    val show: SimklShow? = null,
    val episode: SimklEpisode? = null
)

// ---- All-items (watched state) DTOs ----------------------------------------

/** sync/all-items groups the library by type. anime is separate from shows on SimKL
 *  but carries the same shape, so we treat the two the same. */
@Serializable
data class SimklAllItems(
    val movies: List<SimklMovieItem> = emptyList(),
    val shows: List<SimklShowItem> = emptyList(),
    val anime: List<SimklShowItem> = emptyList()
)

@Serializable
data class SimklMovieItem(
    val last_watched_at: String? = null,
    val status: String? = null,
    val movie: SimklMovie? = null
)

@Serializable
data class SimklShowItem(
    val last_watched_at: String? = null,
    val status: String? = null,
    val show: SimklShow? = null,
    val seasons: List<SimklSeasonWatched> = emptyList()
)

@Serializable
data class SimklSeasonWatched(
    val number: Int? = null,
    val episodes: List<SimklEpisodeWatched> = emptyList()
)

@Serializable
data class SimklEpisodeWatched(
    val number: Int? = null,
    val watched_at: String? = null
)
