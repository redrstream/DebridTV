package io.debridtv.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
