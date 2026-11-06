package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "playback")

data class ResumeState(val trackId: Long?, val positionMs: Long)

object PlaybackPrefs {
    private val KEY_TRACK = longPreferencesKey("last_track_id")
    private val KEY_POS   = longPreferencesKey("last_position_ms")
    private val KEY_POSITIONS = stringPreferencesKey("track_positions")
    private const val MAX_ENTRIES = 500

    fun observe(context: Context): Flow<ResumeState> =
        context.dataStore.data.map { p ->
            ResumeState(p[KEY_TRACK], p[KEY_POS] ?: 0L)
        }

    suspend fun read(context: Context): ResumeState =
        observe(context).first()

    suspend fun save(context: Context, trackId: Long, positionMs: Long) {
        context.dataStore.edit { p ->
            p[KEY_TRACK] = trackId
            p[KEY_POS] = positionMs
        }
    }

    // ─── PR-L58: per-track position map ────────────────────────
    // JSON: {"123": 45000, "456": 120000}. Pruned to MAX_ENTRIES.
    suspend fun savePosition(context: Context, trackId: Long, positionMs: Long) {
        if (positionMs <= 0L) return
        context.dataStore.edit { p ->
            val current = p[KEY_POSITIONS] ?: "{}"
            val map = try { JSONObject(current) } catch (_: Exception) { JSONObject() }
            map.put(trackId.toString(), positionMs)
            if (map.length() > MAX_ENTRIES) {
                val keys = map.keys().asSequence().toList()
                keys.take(map.length() - MAX_ENTRIES).forEach { map.remove(it) }
            }
            p[KEY_POSITIONS] = map.toString()
        }
    }

    suspend fun readPosition(context: Context, trackId: Long): Long? {
        val p = context.dataStore.data.first()
        val json = p[KEY_POSITIONS] ?: return null
        return try {
            val map = JSONObject(json)
            if (map.has(trackId.toString())) map.getLong(trackId.toString()) else null
        } catch (_: Exception) { null }
    }

    suspend fun clearPosition(context: Context, trackId: Long) {
        context.dataStore.edit { p ->
            val current = p[KEY_POSITIONS] ?: return@edit
            val map = try { JSONObject(current) } catch (_: Exception) { return@edit }
            map.remove(trackId.toString())
            p[KEY_POSITIONS] = map.toString()
        }
    }
}
