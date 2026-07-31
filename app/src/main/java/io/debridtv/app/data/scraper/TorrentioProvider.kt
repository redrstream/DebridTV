package io.debridtv.app.data.scraper

import io.debridtv.app.data.torrentio.TorrentioApi
import io.debridtv.app.domain.StreamSource

/** Wraps the existing Torrentio scraper behind the common provider interface. */
class TorrentioProvider(private val api: TorrentioApi) : SourceProvider {
    override val name = "Torrentio"

    override suspend fun fetch(request: SourceRequest): List<StreamSource> {
        val streams = api.streams(request.type, request.stremioId).streams
        return streams.mapNotNull { StreamSource.from(it) }
    }
}
