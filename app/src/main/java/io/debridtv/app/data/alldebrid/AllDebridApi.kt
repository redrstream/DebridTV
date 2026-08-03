package io.debridtv.app.data.alldebrid

import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * AllDebrid API (v4 + v4.1). Auth is a Bearer header (the `agent`/`version`
 * params and apikey-in-URL were removed in the Jan 2025 changelog).
 *
 * v4.1 change: magnet/status no longer returns download links; the file tree
 * (with links) now comes from the separate v4 magnet/files endpoint.
 */
interface AllDebridApi {

    @POST("v4/user")
    suspend fun user(@Header("Authorization") auth: String): UserResponse

    @FormUrlEncoded
    @POST("v4/magnet/upload")
    suspend fun uploadMagnet(
        @Header("Authorization") auth: String,
        @Field("magnets[]") magnet: String
    ): MagnetUploadResponse

    /** Status of a single magnet. */
    @FormUrlEncoded
    @POST("v4.1/magnet/status")
    suspend fun magnetStatus(
        @Header("Authorization") auth: String,
        @Field("id") id: Long
    ): MagnetStatusOneResponse

    /** Every magnet on the account (no links). */
    @POST("v4.1/magnet/status")
    suspend fun allMagnets(@Header("Authorization") auth: String): MagnetStatusAllResponse

    /** File tree (with download links) for a ready magnet. */
    @FormUrlEncoded
    @POST("v4/magnet/files")
    suspend fun magnetFiles(
        @Header("Authorization") auth: String,
        @Field("id[]") id: Long
    ): MagnetFilesResponse

    @FormUrlEncoded
    @POST("v4/link/unlock")
    suspend fun unlock(
        @Header("Authorization") auth: String,
        @Field("link") link: String
    ): UnlockResponse
}

@Serializable
data class AdError(val code: String = "", val message: String = "")

@Serializable
data class UserResponse(
    val status: String = "",
    val error: AdError? = null,
    val data: UserData? = null
)

@Serializable
data class UserData(val user: AdUser? = null)

@Serializable
data class AdUser(
    val username: String = "",
    val email: String = "",
    val isPremium: Boolean = false,
    val premiumUntil: Long? = null
)

@Serializable
data class MagnetUploadResponse(
    val status: String = "",
    val error: AdError? = null,
    val data: UploadData? = null
)

@Serializable
data class UploadData(val magnets: List<UploadedMagnet> = emptyList())

@Serializable
data class UploadedMagnet(
    val magnet: String? = null,
    val hash: String? = null,
    val name: String? = null,
    val id: Long? = null,
    val size: Long? = null,
    val ready: Boolean = false,
    val error: AdError? = null
)

@Serializable
data class MagnetStatusOneResponse(
    val status: String = "",
    val error: AdError? = null,
    val data: StatusOneData? = null
)

@Serializable
data class StatusOneData(val magnets: MagnetInfo? = null)

@Serializable
data class MagnetStatusAllResponse(
    val status: String = "",
    val error: AdError? = null,
    val data: StatusAllData? = null
)

@Serializable
data class StatusAllData(val magnets: List<MagnetInfo> = emptyList())

@Serializable
data class MagnetInfo(
    val id: Long? = null,
    val filename: String? = null,
    val status: String? = null,
    val statusCode: Int? = null,
    val size: Long? = null,
    val downloaded: Long? = null,
    val seeders: Int? = null,
    val downloadSpeed: Long? = null,
    val uploadDate: Long? = null,
    val completionDate: Long? = null
) {
    val isReady: Boolean get() = statusCode == 4
    val isError: Boolean get() = (statusCode ?: 0) > 4
    val progressPercent: Int
        get() = if (size != null && size > 0 && downloaded != null)
            ((downloaded.toDouble() / size) * 100).toInt().coerceIn(0, 100) else 0
}

@Serializable
data class MagnetFilesResponse(
    val status: String = "",
    val error: AdError? = null,
    val data: MagnetFilesData? = null
)

@Serializable
data class MagnetFilesData(val magnets: List<MagnetFilesEntry> = emptyList())

@Serializable
data class MagnetFilesEntry(val files: List<FileNode> = emptyList())

/** A node in the magnet file tree: n=name, s=size, l=link (files), e=entries (folders). */
@Serializable
data class FileNode(
    val n: String? = null,
    val s: Long? = null,
    val l: String? = null,
    val e: List<FileNode> = emptyList()
)

@Serializable
data class UnlockResponse(
    val status: String = "",
    val error: AdError? = null,
    val data: UnlockData? = null
)

@Serializable
data class UnlockData(
    val link: String? = null,
    val filename: String? = null,
    val filesize: Long? = null,
    val delayed: Long? = null
)
