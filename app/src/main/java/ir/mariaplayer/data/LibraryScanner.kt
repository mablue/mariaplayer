package ir.mariaplayer.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LibraryScanner {

    fun hasAudioPermission(context: Context): Boolean {
        val perm = requiredPermission()
        return ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection: Uri = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val base = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val pathCol = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.RELATIVE_PATH
        else
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        base += pathCol
        val projection = base.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val out = ArrayList<Track>()
        context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pCol = c.getColumnIndex(pathCol)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val raw = if (pCol >= 0) c.getString(pCol) else null
                out += Track(
                    id = id,
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = c.getString(artistCol)?.takeUnless { it.isBlank() || it == "<unknown>" },
                    album = c.getString(albumCol)?.takeUnless { it.isBlank() || it == "<unknown>" },
                    durationMs = c.getLong(durCol),
                    contentUri = uri.toString(),
                    folder = folderName(raw),
                )
            }
        }
        out
    }

    private fun folderName(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown"
        val trimmed = raw.trimEnd('/')
        val seg = if (trimmed.contains('/')) trimmed.substringAfterLast('/') else trimmed
        return seg.ifBlank { "Root" }
    }
}
