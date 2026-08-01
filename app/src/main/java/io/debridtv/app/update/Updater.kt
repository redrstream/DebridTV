package io.debridtv.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.debridtv.app.BuildConfig
import io.debridtv.app.data.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.create
import retrofit2.http.GET
import retrofit2.http.Headers
import java.io.File
import java.util.concurrent.TimeUnit

/** The subset of GitHub's "latest release" JSON we care about. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = ""
)

interface GithubApi {
    // Unauthenticated read of the newest published release. Works because the repo
    // is public; the download URLs it returns are anonymously fetchable.
    @Headers("Accept: application/vnd.github+json", "User-Agent: DebridTV")
    @GET("repos/redrstream/DebridTV/releases/latest")
    suspend fun latestRelease(): GithubRelease
}

/** What the UI needs once an update is found. */
data class UpdateInfo(
    val versionName: String,   // e.g. "0.1.4" (tag with any leading v stripped)
    val tag: String,           // e.g. "v0.1.4"
    val apkUrl: String,
    val notes: String
)

/**
 * Self-update from GitHub Releases. Checks whether the latest published release is
 * newer than the running build, downloads its signed APK, and hands it to Android's
 * package installer. In-place update works because CI signs every release with the
 * same keystore this build was signed with.
 */
object UpdateManager {

    private val api: GithubApi by lazy {
        Net.retrofit("https://api.github.com/").create()
    }

    // Dedicated client with a roomier read timeout for the APK download itself.
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns update info only if GitHub's latest release is strictly newer than the
     * running build AND actually ships an .apk asset. Returns null when up to date.
     * Throws on network/parse failure so the UI can show an error.
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = api.latestRelease()
        if (release.draft) return@withContext null
        val remote = parseVersion(release.tagName) ?: return@withContext null
        val current = parseVersion(BuildConfig.VERSION_NAME) ?: return@withContext null
        if (compareVersions(remote, current) <= 0) return@withContext null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        UpdateInfo(
            versionName = release.tagName.trim().trimStart('v', 'V'),
            tag = release.tagName,
            apkUrl = apk.browserDownloadUrl,
            notes = release.body.trim()
        )
    }

    /**
     * Download the APK into the app cache, reporting 0..100 progress. Old downloads
     * are cleared first so the cache doesn't accumulate stale APKs.
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val response = downloadClient.newCall(Request.Builder().url(info.apkUrl).build()).execute()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("Download failed (HTTP ${it.code})")
            val body = it.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength()
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { old -> old.delete() }
            val outFile = File(dir, "DebridTV-${info.versionName}.apk")
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        readTotal += read
                        if (total > 0) {
                            val pct = ((readTotal * 100) / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            onProgress(100)
            outFile
        }
    }

    /** Launch the system package installer for a downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ---- version comparison --------------------------------------------------

    /** "v0.1.4" / "0.1.4-2" -> [0,1,4]. Null if no digits are found. */
    private fun parseVersion(raw: String): List<Int>? {
        val parts = raw.trim().trimStart('v', 'V')
            .split('.', '-', '_', '+')
            .mapNotNull { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() }
        return parts.ifEmpty { null }
    }

    /** Standard left-to-right numeric compare; missing segments count as 0. */
    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
