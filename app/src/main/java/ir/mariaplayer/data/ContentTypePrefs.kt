package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.contentTypeStore by preferencesDataStore(name = "content_types")

/**
 * C21: per-track user overrides for ContentType. When absent, the classifier
 * result is used. When present, it wins over the heuristic.
 */
object ContentTypePrefs {
    private fun key(id: Long) = stringPreferencesKey("ct_$id")

    suspend fun read(context: Context, trackId: Long): ContentType? {
        val p = context.contentTypeStore.data.first()
        val v = p[key(trackId)] ?: return null
        return try { ContentType.valueOf(v) } catch (_: Exception) { null }
    }

    suspend fun set(context: Context, trackId: Long, type: ContentType) {
        context.contentTypeStore.edit { p -> p[key(trackId)] = type.name }
    }

    suspend fun clear(context: Context, trackId: Long) {
        context.contentTypeStore.edit { p -> p.remove(key(trackId)) }
    }

    fun observeAll(context: Context): Flow<Map<Long, ContentType>> =
        context.contentTypeStore.data.map { p ->
            p.asMap().entries.mapNotNull { (k, v) ->
                val name = k.name
                if (!name.startsWith("ct_")) return@mapNotNull null
                val id = name.removePrefix("ct_").toLongOrNull() ?: return@mapNotNull null
                val type = try { ContentType.valueOf(v as String) } catch (_: Exception) { null }
                if (type != null) id to type else null
            }.toMap()
        }
}
