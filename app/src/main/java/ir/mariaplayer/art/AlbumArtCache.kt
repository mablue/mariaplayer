package ir.mariaplayer.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PR-L56: Album art cache — memory LruCache + disk cache, 256px max.
 * Uses loadThumbnail on API 29+, MediaMetadataRetriever fallback for 26-28.
 */
object AlbumArtCache {

    private const val SIZE_PX = 256
    private const val MEMORY_ITEMS = 80

    private val memory = object : LruCache<Long, Bitmap>(MEMORY_ITEMS) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1
    }

    suspend fun get(context: Context, trackId: Long, uriString: String): Bitmap? {
        memory.get(trackId)?.let { return it }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "covers").also { it.mkdirs() }
            val disk = File(dir, "$trackId.jpg")

            if (disk.exists()) {
                val cached = BitmapFactory.decodeFile(disk.absolutePath)
                if (cached != null) {
                    memory.put(trackId, cached)
                    return@withContext cached
                }
            }

            val bmp = extract(context, Uri.parse(uriString)) ?: return@withContext null
            runCatching {
                disk.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
            memory.put(trackId, bmp)
            bmp
        }
    }

    fun peek(trackId: Long): Bitmap? = memory.get(trackId)

    fun clear(context: Context) {
        memory.evictAll()
        runCatching { File(context.cacheDir, "covers").deleteRecursively() }
    }

    private fun extract(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(SIZE_PX, SIZE_PX), null)
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                val raw = mmr.embeddedPicture ?: return@runCatching null
                val original = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@runCatching null
                if (original.width == SIZE_PX && original.height == SIZE_PX) {
                    original
                } else {
                    val scaled = Bitmap.createScaledBitmap(original, SIZE_PX, SIZE_PX, true)
                    if (scaled !== original) original.recycle()
                    scaled
                }
            } finally {
                runCatching { mmr.release() }
            }
        }
    }.getOrNull()
}
