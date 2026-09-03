package io.debridtv.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.debridtv.app.data.net.Net
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "debridtv")

private val KEY_APIKEY = stringPreferencesKey("alldebrid_api_key")
private val KEY_HISTORY = stringPreferencesKey("watch_history_json")
private val KEY_SURROUND = booleanPreferencesKey("prefer_surround")
private val KEY_SIMKL_TOKEN = stringPreferencesKey("simkl_access_token")
private val KEY_SIMKL_ENABLED = booleanPreferencesKey("simkl_enabled")
private val KEY_SIMKL_LAST_PULL = longPreferencesKey("simkl_last_pull_at")
private val KEY_SIMKL_LAST_WATCHED = longPreferencesKey("simkl_last_watched_sync_at")

/** Stores the AllDebrid API key + playback preferences locally on the device. */
class SettingsStore(private val context: Context) {

    val apiKey: Flow<String?> = context.dataStore.data.map { it[KEY_APIKEY]?.takeIf(String::isNotBlank) }

    suspend fun apiKeyOrNull(): String? = apiKey.first()

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[KEY_APIKEY] = value.trim() }
    }

    suspend fun clearApiKey() {
        context.dataStore.edit { it.remove(KEY_APIKEY) }
    }

    /**
     * When true (default), the player is allowed to pick surround / Atmos audio
     * tracks even if the TV's own speakers can't decode them, so the encoded
     * bitstream can pass through to a receiver/soundbar over HDMI/eARC.
     */
    val preferSurround: Flow<Boolean> = context.dataStore.data.map { it[KEY_SURROUND] ?: true }

    suspend fun setPreferSurround(value: Boolean) {
        context.dataStore.edit { it[KEY_SURROUND] = value }
    }

    // ---- SimKL cross-device sync ------------------------------------------

    /** True when SimKL sync is switched on (default on once connected). */
    val simklEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SIMKL_ENABLED] ?: true }

    /** Whether an account is currently linked (an access token is stored). */
    val simklConnected: Flow<Boolean> =
        context.dataStore.data.map { !it[KEY_SIMKL_TOKEN].isNullOrBlank() }

    suspend fun simklEnabledOrDefault(): Boolean = simklEnabled.first()

    suspend fun simklTokenOrNull(): String? =
        context.dataStore.data.first()[KEY_SIMKL_TOKEN]?.takeIf { it.isNotBlank() }

    suspend fun setSimklToken(token: String) {
        context.dataStore.edit { it[KEY_SIMKL_TOKEN] = token }
    }

    suspend fun setSimklEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_SIMKL_ENABLED] = value }
    }

    /** Epoch-ms of the last successful playback pull, used to throttle syncs
     *  (SimKL asks apps not to poll more than every 15–30 min). 0 if never. */
    suspend fun simklLastPullAt(): Long = context.dataStore.data.first()[KEY_SIMKL_LAST_PULL] ?: 0L

    suspend fun setSimklLastPullAt(value: Long) {
        context.dataStore.edit { it[KEY_SIMKL_LAST_PULL] = value }
    }

    /** Epoch-ms of the last watched-history sync (a separate, less frequent pull that
     *  brings finished/watched state across from other devices). 0 if never. */
    suspend fun simklLastWatchedSyncAt(): Long = context.dataStore.data.first()[KEY_SIMKL_LAST_WATCHED] ?: 0L

    suspend fun setSimklLastWatchedSyncAt(value: Long) {
        context.dataStore.edit { it[KEY_SIMKL_LAST_WATCHED] = value }
    }

    suspend fun clearSimkl() {
        context.dataStore.edit {
            it.remove(KEY_SIMKL_TOKEN)
            it.remove(KEY_SIMKL_LAST_PULL)
            it.remove(KEY_SIMKL_LAST_WATCHED)
        }
    }
}

@Serializable
data class HistoryEntry(
    val key: String,
    val type: String,
    val title: String,
    val poster: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val streamName: String? = null,
    val updatedAt: Long = 0,
    // True when the user explicitly marked this watched (no real playback position).
    val watched: Boolean = false
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * Treat the last 5 minutes as "done": once you're this close to the end we
     * roll over to the next episode and consider this one watched.
     */
    val isFinished: Boolean
        get() = durationMs > 0 && positionMs >= durationMs - 300_000

    /** Watched either by finishing playback or by an explicit manual mark. */
    val isWatched: Boolean get() = watched || isFinished
}

/** Watch history + resume positions, persisted as a small JSON blob. */
class HistoryStore(private val context: Context) {

    private val serializer = ListSerializer(HistoryEntry.serializer())

    val history: Flow<List<HistoryEntry>> = context.dataStore.data.map { prefs ->
        decode(prefs[KEY_HISTORY]).sortedByDescending { it.updatedAt }
    }

    suspend fun get(key: String): HistoryEntry? = decode(current()).firstOrNull { it.key == key }

    suspend fun upsert(entry: HistoryEntry) {
        context.dataStore.edit { prefs ->
            val list = decode(prefs[KEY_HISTORY]).toMutableList()
            list.removeAll { it.key == entry.key }
            list.add(0, entry)
            // keep the list bounded
            val trimmed = list.take(200)
            prefs[KEY_HISTORY] = Net.json.encodeToString(serializer, trimmed)
        }
    }

    suspend fun remove(key: String) {
        context.dataStore.edit { prefs ->
            val list = decode(prefs[KEY_HISTORY]).filterNot { it.key == key }
            prefs[KEY_HISTORY] = Net.json.encodeToString(serializer, list)
        }
    }

    /**
     * Remove every entry for a show — the movie/show row itself ("tt123") and all
     * of its episode rows ("tt123:season:episode"). Used when the user removes a
     * collapsed series card from Continue Watching.
     */
    suspend fun removeShow(showId: String) {
        context.dataStore.edit { prefs ->
            val list = decode(prefs[KEY_HISTORY])
                .filterNot { it.key == showId || it.key.startsWith("$showId:") }
            prefs[KEY_HISTORY] = Net.json.encodeToString(serializer, list)
        }
    }

    /**
     * Manually mark an episode/movie watched or unwatched. Marking watched keeps
     * any real playback position but flips the [HistoryEntry.watched] flag (and
     * bumps updatedAt so it becomes the "furthest" point). Unwatching drops the
     * entry entirely.
     */
    suspend fun setWatched(key: String, type: String, title: String, poster: String?, watched: Boolean) {
        val existing = get(key)
        if (!watched) {
            // Keep a real resume position if there is one; only a pure manual-watched
            // marker (no playback progress) is dropped entirely.
            if (existing != null && existing.positionMs > 0) {
                upsert(existing.copy(watched = false))
            } else {
                remove(key)
            }
            return
        }
        val entry = (existing ?: HistoryEntry(key = key, type = type, title = title, poster = poster))
            .copy(watched = true, updatedAt = System.currentTimeMillis())
        upsert(entry)
    }

    private suspend fun current(): String? = context.dataStore.data.first()[KEY_HISTORY]

    private fun decode(raw: String?): List<HistoryEntry> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { Net.json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}
