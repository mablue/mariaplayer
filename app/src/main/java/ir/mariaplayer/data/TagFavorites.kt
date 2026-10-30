package ir.mariaplayer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Favorites stored INSIDE the audio file's COMMENT tag as a marker.
 *
 * Why COMMENT: it exists in every format jaudiotagger supports
 * (MP3/ID3v2, FLAC/Vorbis, OGG, M4A/MP4). No format-specific code.
 *
 * Trade-off: a file whose COMMENT already has user text will have
 * that text preserved (we append/prepend the marker), but if the file
 * is read-only or malformed, the write fails silently and is logged.
 */
object TagFavorites {

    private const val TAG = "TagFavorites"
    private const val MARKER = "[MARIA_FAV]"

    /** Read a single file's tag. Returns true if marker present. */
    suspend fun read(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val temp = copyToTemp(context, uri) ?: return@withContext false
            try {
                val audio = AudioFileIO.read(temp)
                val tag = audio.tagOrCreateAndSetDefault
                val comment = tag.getFirst(FieldKey.COMMENT).orEmpty()
                comment.contains(MARKER)
            } finally {
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "read failed for $uri: ${e.message}")
            false
        }
    }

    /**
     * Write (or clear) the favorite marker in one file.
     * Must be called AFTER write permission is granted (createWriteRequest).
     * Returns true on success.
     */
    suspend fun write(context: Context, uri: Uri, isFavorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val temp = copyToTemp(context, uri) ?: return@withContext false
                try {
                    val audio = AudioFileIO.read(temp)
                    val tag = audio.tagOrCreateAndSetDefault
                    val existing = tag.getFirst(FieldKey.COMMENT).orEmpty()

                    val newComment = when {
                        isFavorite && existing.contains(MARKER) -> existing
                        isFavorite -> if (existing.isBlank()) MARKER else "$existing $MARKER"
                        else -> existing.replace(MARKER, "").replace("  ", " ").trim()
                    }
                    tag.setField(FieldKey.COMMENT, newComment)
                    AudioFileIO.write(audio)

                    // copy modified temp back over the original content URI
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: return@withContext false
                    true
                } finally {
                    temp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "write failed for $uri: ${e.message}")
                false
            }
        }

    private fun copyToTemp(context: Context, uri: Uri): File? = try {
        val ext = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "mp3"
        val f = File.createTempFile("tag_", ".$ext", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: return null
        f
    } catch (e: Exception) {
        Log.w(TAG, "copyToTemp failed for $uri: ${e.message}")
        null
    }
}
