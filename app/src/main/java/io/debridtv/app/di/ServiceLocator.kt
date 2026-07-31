package io.debridtv.app.di

import android.content.Context
import io.debridtv.app.data.alldebrid.AllDebridApi
import io.debridtv.app.data.alldebrid.AllDebridClient
import io.debridtv.app.data.cinemeta.CinemetaService
import io.debridtv.app.data.net.Net
import io.debridtv.app.data.prefs.HistoryStore
import io.debridtv.app.data.prefs.SettingsStore
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
}
