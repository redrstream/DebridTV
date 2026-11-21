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

    /** All paused playbacks (movies + episodes, progress < 80%) for cross-device
     *  resume — a flat array of sessions each carrying a `progress` percent (no
     *  runtime). */
    @GET("sync/playback")
    suspend fun playbackAll(@Header("Authorization") auth: String): List<SimklPlaybackItem>

    // ---- Sync activities (what changed?) -----------------------------------

    /** Cheap "last modified" timestamps per list. SimKL requires calling this FIRST
     *  and only pulling the lists that moved — polling all-items directly gets the
     *  client_id suspended. */
    @GET("sync/activities")
    suspend fun activities(@Header("Authorization") auth: String): SimklActivities

    // ---- Sync all-items (watched / finished state) -------------------------

    /**
     * The user's library grouped by media type. `extended=full` is REQUIRED for the
     * per-season episode lists (without it shows come back with no episodes at all —
     * the v0.1.25–0.1.32 bug that meant watched episodes never crossed TVs), and
     * `episode_watched_at=yes` adds each episode's watched time. `date_from` = the
     * saved activities `all` value returns only items changed after it.
     */
    @GET("sync/all-items")
    suspend fun allItems(
        @Header("Authorization") auth: String,
        @Query("date_from") dateFrom: String,
        @Query("extended") extended: String = "full",
        @Query("episode_watched_at") episodeWatchedAt: String = "yes",
        @Query("include_all_episodes") includeAllEpisodes: String = "original"
    ): SimklAllItems

    // ---- Sync history (explicit watched / unwatched marks) -----------------

    /** Explicitly add movies / episodes to watched history (used for a manual
     *  "Mark watched" on the TV, which has no scrobble session behind it). */
    @POST("sync/history")
    suspend fun addToHistory(
        @Header("Authorization") auth: String,
        @Body body: SimklHistoryReq
    ): Response<Unit>

    /** Remove movies / episodes from watched history ("Mark unwatched"). */
    @POST("sync/history/remove")
    suspend fun removeFromHistory(
        @Header("Authorization") auth: String,
        @Body body: SimklHistoryReq
    ): Response<Unit>
}

// ---- History DTOs ------------------------------------------------------------

@Serializable
data class SimklHistoryReq(
    val movies: List<SimklHistoryMovie>? = null,
    val shows: List<SimklHistoryShow>? = null
)

@Serializable
data class SimklHistoryMovie(val ids: SimklIds, val watched_at: String? = null)

@Serializable
data class SimklHistoryShow(val ids: SimklIds, val seasons: List<SimklHistorySeason>)

@Serializable
data class SimklHistorySeason(val number: Int, val episodes: List<SimklHistoryEpisode>)

@Serializable
data class SimklHistoryEpisode(val number: Int, val watched_at: String? = null)

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
    val anime: SimklShow? = null,
    val episode: SimklEpisode? = null
)

// ---- Activities DTOs ---------------------------------------------------------

@Serializable
data class SimklActivities(
    val all: String? = null,
    val tv_shows: SimklDomainActivity? = null,
    val anime: SimklDomainActivity? = null,
    val movies: SimklDomainActivity? = null
)

@Serializable
data class SimklDomainActivity(
    val all: String? = null,
    val playback: String? = null,
    val plantowatch: String? = null,
    val watching: String? = null,
    val completed: String? = null,
    val hold: String? = null,
    val dropped: String? = null,
    val removed_from_list: String? = null
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
    val anime: SimklShow? = null,
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
