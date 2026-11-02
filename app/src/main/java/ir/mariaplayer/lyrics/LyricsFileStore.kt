package ir.mariaplayer.lyrics

import android.content.Context
import java.io.File

/**
 * PR-L17: replace DataStore for lyric payloads.
 *
 * DataStore is designed for small key-value pairs (KB range). Storing full
 * LRC strings (potentially hundreds of lines) in it caused OOM at
 * DataStoreImpl$writeActor. Lyrics now live in filesDir/lyrics/.
 *
 * Only the small offset (Long) and translation language (String) stay in
 * LyricsCache's DataStore. Everything else is a plain file.
 */
object LyricsFileStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "lyrics").apply { mkdirs() }

    fun syncedFile(context: Context, trackId: Long): File =
        File(dir(context), "${trackId}_synced.lrc")

    fun plainFile(context: Context, trackId: Long): File =
        File(dir(context), "${trackId}_plain.txt")

    fun translationFile(context: Context, trackId: Long): File =
        File(dir(context), "${trackId}_tr.txt")

    fun readSynced(context: Context, trackId: Long): String? =
        syncedFile(context, trackId).takeIf { it.exists() }?.readText()

    fun readPlain(context: Context, trackId: Long): String? =
        plainFile(context, trackId).takeIf { it.exists() }?.readText()

    fun writeSynced(context: Context, trackId: Long, text: String?) {
        val f = syncedFile(context, trackId)
        if (text.isNullOrBlank()) f.delete() else f.writeText(text)
    }

    fun writePlain(context: Context, trackId: Long, text: String?) {
        val f = plainFile(context, trackId)
        if (text.isNullOrBlank()) f.delete() else f.writeText(text)
    }

    fun writeTranslation(context: Context, trackId: Long, text: String?) {
        val f = translationFile(context, trackId)
        if (text.isNullOrBlank()) f.delete() else f.writeText(text)
    }

    fun readTranslation(context: Context, trackId: Long): String? =
        translationFile(context, trackId).takeIf { it.exists() }?.readText()

    fun deleteAll(context: Context, trackId: Long) {
        syncedFile(context, trackId).delete()
        plainFile(context, trackId).delete()
        translationFile(context, trackId).delete()
    }
}
