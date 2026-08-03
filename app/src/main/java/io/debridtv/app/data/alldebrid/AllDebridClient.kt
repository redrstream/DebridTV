package io.debridtv.app.data.alldebrid

import io.debridtv.app.data.prefs.SettingsStore
import kotlinx.coroutines.delay

class AllDebridException(message: String) : Exception(message)

/**
 * High-level wrapper around [AllDebridApi] (v4/v4.1): injects the Bearer auth
 * header, turns error envelopes into exceptions, and adds the "wait until a
 * magnet is ready" polling loop used for playback and auto-queue.
 */
class AllDebridClient(
    private val api: AllDebridApi,
    private val settings: SettingsStore
) {
    private suspend fun auth(): String {
        val key = settings.apiKeyOrNull()
            ?: throw AllDebridException("No AllDebrid API key set. Add it in Settings.")
        return "Bearer $key"
    }

    suspend fun validate(): AdUser {
        val res = api.user(auth())
        res.error?.let { throw AllDebridException(it.message.ifBlank { it.code }) }
        return res.data?.user ?: throw AllDebridException("Unexpected AllDebrid response")
    }

    suspend fun uploadMagnet(magnetOrHash: String): UploadedMagnet {
        val res = api.uploadMagnet(auth(), magnetOrHash)
        res.error?.let { throw AllDebridException(it.message.ifBlank { it.code }) }
        val m = res.data?.magnets?.firstOrNull()
            ?: throw AllDebridException("AllDebrid did not accept the magnet")
        m.error?.let { throw AllDebridException(it.message.ifBlank { it.code }) }
        return m
    }

    suspend fun status(id: Long): MagnetInfo {
        val res = api.magnetStatus(auth(), id)
        res.error?.let { throw AllDebridException(it.message.ifBlank { it.code }) }
        return res.data?.magnets ?: throw AllDebridException("Magnet not found")
    }

    suspend fun listMagnets(): List<MagnetInfo> {
        val res = api.allMagnets(auth())
        res.error?.let { throw AllDebridException(it.message.ifBlank { it.code }) }
        // Most-recently-finished first. completionDate is when the download actually
        // completed (what the user thinks of as "most recent"); fall back to uploadDate,
        // then id, since older/partial entries may not carry a completion timestamp.
        return res.data?.magnets.orEmpty()
            .sortedByDescending { it.completionDate ?: it.uploadDate ?: it.id ?: 0L }
    }

    /** Flattened list of downloadable files (folders recursed) for a ready magnet. */
    suspend fun files(id: Long): List<FileNode> {
        val res = api.magnetFiles(auth(), id)
        res.error?.let { throw AllDebridException(it.message.ifBlank { it.code }) }
        val tree = res.data?.magnets?.firstOrNull()?.files.orEmpty()
        return flatten(tree)
    }

    suspend fun unlock(link: String): String {
        val res = api.unlock(auth(), link)
        res.error?.let { throw AllDebridException(it.message.ifBlank { it.code }) }
        val data = res.data
        return when {
            !data?.link.isNullOrBlank() -> data!!.link!!
            (data?.delayed ?: 0) > 0 ->
                throw AllDebridException("Link is still being prepared by AllDebrid — try again shortly")
            else -> throw AllDebridException("Could not unlock link")
        }
    }

    /**
     * Adds the magnet (if not already present) and polls until AllDebrid reports
     * it Ready (statusCode 4). Cached magnets return almost immediately.
     */
    suspend fun ensureReady(
        magnetOrHash: String,
        timeoutMs: Long = 180_000,
        pollIntervalMs: Long = 3_000,
        onProgress: (MagnetInfo) -> Unit = {}
    ): MagnetInfo {
        val uploaded = uploadMagnet(magnetOrHash)
        val id = uploaded.id ?: throw AllDebridException("AllDebrid returned no magnet id")

        var elapsed = 0L
        var info = status(id)
        onProgress(info)
        while (!info.isReady && elapsed < timeoutMs) {
            if (info.isError) throw AllDebridException("AllDebrid error: ${info.status ?: "unknown"}")
            delay(pollIntervalMs)
            elapsed += pollIntervalMs
            info = status(id)
            onProgress(info)
        }
        if (!info.isReady) throw AllDebridException("Timed out waiting for AllDebrid to cache this source")
        return info
    }

    private fun flatten(nodes: List<FileNode>): List<FileNode> =
        nodes.flatMap { if (!it.l.isNullOrBlank()) listOf(it) else flatten(it.e) }
}
