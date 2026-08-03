package io.debridtv.app.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Central place that builds the JSON parser, HTTP clients and Retrofit
 * instances. Three OkHttp clients are used: a "logging" one for the open
 * scraper APIs, a "plain" one for AllDebrid (auth key never logged), and a
 * "cached" one used ONLY for Cinemeta metadata/catalogs — scraper source lists
 * and AllDebrid unlock links must always stay live, so they are never cached.
 */
object Net {

    private lateinit var appContext: Context

    /** Must be called once at startup (from ServiceLocator.init) before any request. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val contentType = "application/json".toMediaType()

    private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    private fun debugLogger(level: HttpLoggingInterceptor.Level) = HttpLoggingInterceptor().apply {
        this.level = if (io.debridtv.app.BuildConfig.DEBUG) level else HttpLoggingInterceptor.Level.NONE
    }

    private val loggingClient: OkHttpClient by lazy {
        baseClient().addInterceptor(debugLogger(HttpLoggingInterceptor.Level.BASIC)).build()
    }

    private val plainClient: OkHttpClient by lazy { baseClient().build() }

    // Short-timeout client for cheaply probing whether a stream link is actually serving
    // bytes yet (a freshly-cached AllDebrid magnet reports Ready before its CDN edge can
    // stream). Kept separate so a cold edge fails fast instead of hanging on the long
    // read timeout the API clients use.
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * True when [url] is actually streamable right now — one tiny ranged GET for the first
     * couple of bytes. Used to wait out a just-cached source only as long as it genuinely
     * needs, instead of a blind fixed delay. Any failure (cold edge, HTTP error, timeout)
     * returns false so the caller retries.
     */
    suspend fun isStreamServable(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Range", "bytes=0-1").get().build()
            probeClient.newCall(req).execute().use { resp ->
                resp.isSuccessful && (resp.body?.byteStream()?.read() ?: -1) >= 0
            }
        } catch (_: Exception) {
            false
        }
    }

    fun retrofit(baseUrl: String, withLogging: Boolean = true): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(if (withLogging) loggingClient else plainClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

    /**
     * For AllDebrid: logs full request/response bodies in debug builds (to aid
     * debugging response shapes) but REDACTS the Authorization header so the API
     * key never reaches logcat. NONE in release builds. Never cached.
     */
    private val authRedactedClient: OkHttpClient by lazy {
        val logger = debugLogger(HttpLoggingInterceptor.Level.BODY).apply { redactHeader("Authorization") }
        baseClient().addInterceptor(logger).build()
    }

    fun retrofitAuthRedacted(baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authRedactedClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

    // ---- Cached client (Cinemeta only) -------------------------------------

    private val cache: Cache by lazy {
        Cache(File(appContext.cacheDir, "http-cache"), 20L * 1024 * 1024) // 20 MB
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // assume online if we can't tell
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Fresh-lifetime per Cinemeta endpoint, in seconds. Popular/genre catalogs
     * change ~daily so they cache for 12 h; a title's metadata for 3 h (so a new
     * episode of an ongoing series shows up reasonably soon); search stays near
     * live. Returns null for anything we shouldn't cache.
     */
    private fun maxAgeSeconds(url: HttpUrl): Int? {
        if (url.host != "v3-cinemeta.strem.io") return null
        val path = url.encodedPath
        return when {
            path.contains("search=") -> 60                 // 1 min
            path.startsWith("/catalog/") -> 12 * 60 * 60   // 12 h (popular + genre)
            path.startsWith("/meta/") -> 3 * 60 * 60       // 3 h
            else -> null
        }
    }

    // Network interceptor: rewrite the server's caching headers so OkHttp will
    // store the response for our chosen lifetime (Cinemeta sends none/no-cache).
    private val setCacheHeaders = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val maxAge = maxAgeSeconds(chain.request().url) ?: return@Interceptor response
        response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Cache-Control")
            .header("Cache-Control", "public, max-age=$maxAge")
            .build()
    }

    // Application interceptor: when offline, ask the cache to serve a stale copy
    // (up to a few days) instead of failing, so the app still shows something.
    private val offlineFallback = Interceptor { chain ->
        val request = if (!isOnline()) {
            chain.request().newBuilder()
                .header("Cache-Control", "public, only-if-cached, max-stale=${3 * 24 * 60 * 60}")
                .build()
        } else chain.request()
        chain.proceed(request)
    }

    private val cachedClient: OkHttpClient by lazy {
        baseClient()
            .cache(cache)
            .addInterceptor(offlineFallback)
            .addNetworkInterceptor(setCacheHeaders)
            .addInterceptor(debugLogger(HttpLoggingInterceptor.Level.BASIC))
            .build()
    }

    /** Retrofit backed by the disk-cached client. Use for Cinemeta only. */
    fun retrofitCached(baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(cachedClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
}
