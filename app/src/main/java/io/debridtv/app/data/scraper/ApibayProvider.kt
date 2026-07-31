package io.debridtv.app.data.scraper

import io.debridtv.app.domain.StreamSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * apibay (The Pirate Bay's JSON endpoint) — general-purpose query search. Covers
 * content the specialised indexers miss. Results are unfiltered, so we drop the
 * "no results" sentinel and low-seed noise.
 */
interface ApibayApi {
    @GET("q.php")
    suspend fun search(
        @Query("q") query: String,
        @Query("cat") cat: String = "200" // 200 = Video
    ): List<ApibayItem>
}

@Serializable
data class ApibayItem(
    val name: String = "",
    @SerialName("info_hash") val infoHash: String = "",
    val seeders: String = "0",
    val size: String = "0"
)

class ApibayProvider(private val api: ApibayApi) : SourceProvider {
    override val name = "TPB"

    override suspend fun fetch(request: SourceRequest): List<StreamSource> {
        val query = request.queryString() ?: return emptyList()
        val items = api.search(query)
        return items.mapNotNull { item ->
            val hash = item.infoHash.lowercase()
            if (hash.isBlank() || hash.all { it == '0' }) return@mapNotNull null
            if (item.name.equals("No results returned", ignoreCase = true)) return@mapNotNull null
            val seeds = item.seeders.toIntOrNull() ?: 0
            if (seeds <= 0) return@mapNotNull null
            StreamSource(
                infoHash = hash,
                fileIdx = null,
                filename = item.name,
                quality = StreamSource.qualityFrom(item.name),
                sizeText = formatBytes(item.size.toLongOrNull()),
                seeders = seeds,
                provider = "TPB",
                rawTitle = item.name
            )
        }
    }
}
