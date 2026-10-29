package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "playback")

data class ResumeState(val trackId: Long?, val positionMs: Long)

object PlaybackPrefs {
    private val KEY_TRACK = longPreferencesKey("last_track_id")
    private val KEY_POS   = longPreferencesKey("last_position_ms")

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
}
