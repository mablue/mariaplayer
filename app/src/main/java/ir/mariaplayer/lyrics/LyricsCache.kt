package ir.mariaplayer.lyrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lyricsStore by preferencesDataStore(name = "lyrics_cache")

/**
 * PR-L17: DataStore holds ONLY small values (offset, translation language).
 * Lyric strings live in LyricsFileStore (plain files). This eliminates the
 * OOM path inside DataStoreImpl$writeActor where large payload writes were
 * allocating with <1% heap free.
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
        val offset = context.lyricsStore.data.first()[
            longPreferencesKey("lyrics_${trackId}_offset")
        ] ?: 0L
        return Cached(
            synced = LyricsFileStore.readSynced(context, trackId),
            plain  = LyricsFileStore.readPlain(context, trackId),
            offsetMs = offset,
        )
    }

    suspend fun writeLyrics(
        context: Context,
        trackId: Long,
        synced: String?,
        plain: String?,
    ) {
        LyricsFileStore.writeSynced(context, trackId, synced)
        LyricsFileStore.writePlain(context, trackId, plain)
    }

    suspend fun clearLyrics(context: Context, trackId: Long) {
        LyricsFileStore.writeSynced(context, trackId, null)
        LyricsFileStore.writePlain(context, trackId, null)
    }

    suspend fun writeOffset(context: Context, trackId: Long, offsetMs: Long) {
        context.lyricsStore.edit { p ->
            p[longPreferencesKey("lyrics_${trackId}_offset")] = offsetMs.coerceIn(-500L, 500L)
        }
    }

    suspend fun readTranslation(context: Context, trackId: Long): Pair<String, String>? {
        val lang = context.lyricsStore.data.first()[
            stringPreferencesKey("lyrics_${trackId}_tr_lang")
        ] ?: return null
        val text = LyricsFileStore.readTranslation(context, trackId) ?: return null
        return lang to text
    }

    suspend fun writeTranslation(
        context: Context,
        trackId: Long,
        lang: String,
        text: String,
    ) {
        context.lyricsStore.edit { p ->
            p[stringPreferencesKey("lyrics_${trackId}_tr_lang")] = lang
        }
        LyricsFileStore.writeTranslation(context, trackId, text)
    }

    suspend fun clearTranslation(context: Context, trackId: Long) {
        context.lyricsStore.edit { p ->
            p.remove(stringPreferencesKey("lyrics_${trackId}_tr_lang"))
        }
        LyricsFileStore.writeTranslation(context, trackId, null)
    }
}
