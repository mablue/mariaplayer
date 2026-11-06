package ir.mariaplayer.lyrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lyricsStore by preferencesDataStore(name = "lyrics_cache")

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
            synced = p[stringPreferencesKey("lyrics_${trackId}_synced")].orEmpty()
                .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
            plain  = p[stringPreferencesKey("lyrics_${trackId}_plain")].orEmpty()
                .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
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

    // ─── PR-L47: translation cache ───
    // One translation list per (trackId, targetLang). Lines joined with \n.
    suspend fun readTranslation(
        context: Context,
        trackId: Long,
        lang: String,
    ): List<String>? {
        val p = context.lyricsStore.data.first()
        val storedLang = p[stringPreferencesKey("lyrics_${trackId}_tr_lang")].orEmpty()
        if (storedLang != lang) return null
        val text = p[stringPreferencesKey("lyrics_${trackId}_tr_text")].orEmpty()
        if (text.isBlank()) return null
        return text.split("\n")
    }

    suspend fun writeTranslation(
        context: Context,
        trackId: Long,
        lang: String,
        lines: List<String>,
    ) {
        context.lyricsStore.edit { p ->
            p[stringPreferencesKey("lyrics_${trackId}_tr_lang")] = lang
            p[stringPreferencesKey("lyrics_${trackId}_tr_text")] = lines.joinToString("\n")
        }
    }

    suspend fun clearTranslation(context: Context, trackId: Long) {
        context.lyricsStore.edit { p ->
            p.remove(stringPreferencesKey("lyrics_${trackId}_tr_lang"))
            p.remove(stringPreferencesKey("lyrics_${trackId}_tr_text"))
        }
    }
}
