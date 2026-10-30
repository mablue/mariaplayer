package ir.mariaplayer.lyrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lyricsStore by preferencesDataStore(name = "lyrics_cache")

/**
 * Per-track cache of lyrics and user offset. Keyed by trackId.
 *
 * Storage format:
 *   "lyrics_{id}_synced" = String (empty = not cached)
 *   "lyrics_{id}_plain"  = String (empty = not cached)
 *   "lyrics_{id}_offset" = Long (default 0)
 *
 * Reads/writes are atomic via DataStore.
 */
object LyricsCache {

    data class Cached(
        val synced: String?,
        val plain: String?,
        val offsetMs: Long,
    ) {
        val hasLyrics: Boolean get() = !synced.isNullOrBlank() || !plain.isNullOrBlank()
    }

    suspend fun read(context: Context, trackId: Long): Cached {
        val p = context.lyricsStore.data.first()
        return Cached(
            synced = p[stringPreferencesKey("lyrics_${trackId}_synced")].orEmpty().ifBlank { null },
            plain  = p[stringPreferencesKey("lyrics_${trackId}_plain")].orEmpty().ifBlank { null },
            offsetMs = p[longPreferencesKey("lyrics_${trackId}_offset")] ?: 0L,
        )
    }

    suspend fun writeLyrics(
        context: Context,
        trackId: Long,
        synced: String?,
        plain: String?,
    ) {
        context.lyricsStore.edit { p ->
            p[stringPreferencesKey("lyrics_${trackId}_synced")] = synced.orEmpty()
            p[stringPreferencesKey("lyrics_${trackId}_plain")]  = plain.orEmpty()
        }
    }

    suspend fun clearLyrics(context: Context, trackId: Long) {
        context.lyricsStore.edit { p ->
            p.remove(stringPreferencesKey("lyrics_${trackId}_synced"))
            p.remove(stringPreferencesKey("lyrics_${trackId}_plain"))
        }
    }

    suspend fun writeOffset(context: Context, trackId: Long, offsetMs: Long) {
        context.lyricsStore.edit { p ->
            p[longPreferencesKey("lyrics_${trackId}_offset")] = offsetMs.coerceIn(-500L, 500L)
        }
    }
}
