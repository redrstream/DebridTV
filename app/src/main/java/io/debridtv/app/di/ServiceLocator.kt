package io.debridtv.app.di

import android.content.Context
import io.debridtv.app.BuildConfig
import io.debridtv.app.data.alldebrid.AllDebridApi
import io.debridtv.app.data.alldebrid.AllDebridClient
import io.debridtv.app.data.cinemeta.CinemetaService
import io.debridtv.app.data.net.Net
import io.debridtv.app.data.prefs.HistoryStore
import io.debridtv.app.data.prefs.SettingsStore
import io.debridtv.app.data.simkl.SimklApi
import io.debridtv.app.data.simkl.SimklClient
import io.debridtv.app.data.simkl.SimklTitleInfo
import io.debridtv.app.data.scraper.ApibayApi
import io.debridtv.app.data.scraper.ApibayProvider
import io.debridtv.app.data.scraper.EztvApi
import io.debridtv.app.data.scraper.EztvProvider
import io.debridtv.app.data.scraper.SourceProvider
import io.debridtv.app.data.scraper.TorrentioProvider
import io.debridtv.app.data.scraper.YtsApi
import io.debridtv.app.data.scraper.YtsProvider
import io.debridtv.app.data.subtitles.OpenSubtitlesService
import io.debridtv.app.data.torrentio.TorrentioService
import io.debridtv.app.domain.MediaRepository
import io.debridtv.app.domain.StreamResolver

/** Minimal manual DI — no framework needed for an app this size. */
object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        Net.init(appContext)
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val history: HistoryStore by lazy { HistoryStore(appContext) }

    private val allDebridApi: AllDebridApi by lazy {
        Net.retrofitAuthRedacted("https://api.alldebrid.com/")
            .create(AllDebridApi::class.java)
    }

    val allDebrid: AllDebridClient by lazy { AllDebridClient(allDebridApi, settings) }

    private val ytsApi: YtsApi by lazy {
        Net.retrofit("https://yts.mx/").create(YtsApi::class.java)
    }
    private val eztvApi: EztvApi by lazy {
        Net.retrofit("https://eztvx.to/").create(EztvApi::class.java)
    }
    private val apibayApi: ApibayApi by lazy {
        Net.retrofit("https://apibay.org/").create(ApibayApi::class.java)
    }

    /** Registered scrapers. Add a new indexer here and it joins every search. */
    private val providers: List<SourceProvider> by lazy {
        listOf(
            TorrentioProvider(TorrentioService.api),
            YtsProvider(ytsApi),
            EztvProvider(eztvApi),
            ApibayProvider(apibayApi)
        )
    }

    val mediaRepo: MediaRepository by lazy {
        MediaRepository(CinemetaService.api, providers, OpenSubtitlesService.api)
    }

    val resolver: StreamResolver by lazy { StreamResolver(allDebrid) }

    private val simklApi: SimklApi by lazy {
        Net.retrofitSimkl(
            baseUrl = "https://api.simkl.com/",
            clientId = BuildConfig.SIMKL_CLIENT_ID,
            appName = "debridtv",
            appVersion = BuildConfig.VERSION_NAME,
            userAgent = "DebridTV/${BuildConfig.VERSION_NAME}"
        ).create(SimklApi::class.java)
    }

    /** Cross-device watch sync (SimKL). Inert unless a client id was baked into
     *  this build and an account is linked in Settings. */
    val simkl: SimklClient by lazy {
        SimklClient(simklApi, settings, history) { type, imdb ->
            mediaRepo.meta(type, imdb)?.let { m ->
                SimklTitleInfo(poster = m.poster, runtimeMin = parseRuntimeMinutes(m.runtime))
            }
        }
    }
}

/** Cinemeta reports runtime as a display string ("148 min", "1h 30min", "45 min").
 *  Pull out total minutes for turning a SimKL progress percent into a resume position. */
private fun parseRuntimeMinutes(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val hours = Regex("(\\d+)\\s*h", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull()
    val mins = Regex("(\\d+)\\s*m", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull()
    if (hours != null || mins != null) {
        return (hours ?: 0) * 60 + (mins ?: 0)
    }
    // Bare number → assume minutes.
    return raw.trim().toIntOrNull()
}
