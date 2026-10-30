package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.playlistStore by preferencesDataStore(name = "playlists")

/**
 * CRUD for user playlists. All writes go through DataStore.edit() so
 * they are atomic and serialized. Reads via observe() stream updates.
 *
 * Concurrency note: read-modify-write is done inside a single edit{}
 * block so two concurrent calls cannot lose each other's changes.
 */
object PlaylistPrefs {
    private val KEY = stringPreferencesKey("playlists_json")

    fun observe(context: Context): Flow<List<Playlist>> =
        context.playlistStore.data.map { p ->
            parsePlaylistsJson(p[KEY].orEmpty())
                .sortedBy { it.name.lowercase() }
        }

    suspend fun snapshot(context: Context): List<Playlist> = observe(context).first()

    /** Creates a new playlist with the given name and returns its id. */
    suspend fun create(context: Context, name: String): Long {
        val now = System.currentTimeMillis()
        var newId = 0L
        context.playlistStore.edit { p ->
            val list = parsePlaylistsJson(p[KEY].orEmpty()).toMutableList()
            newId = (list.maxOfOrNull { it.id } ?: 0L) + 1L
            list += Playlist(
                id = newId,
                name = name.trim().ifBlank { "Untitled" },
                trackIds = emptyList(),
                createdAt = now,
                updatedAt = now,
            )
            p[KEY] = list.toJsonString()
        }
        return newId
    }

    suspend fun delete(context: Context, id: Long) {
        context.playlistStore.edit { p ->
            val list = parsePlaylistsJson(p[KEY].orEmpty())
                .filterNot { it.id == id }
            p[KEY] = list.toJsonString()
        }
    }

    suspend fun rename(context: Context, id: Long, newName: String) {
        context.playlistStore.edit { p ->
            val list = parsePlaylistsJson(p[KEY].orEmpty()).map {
                if (it.id == id) it.copy(
                    name = newName.trim().ifBlank { it.name },
                    updatedAt = System.currentTimeMillis(),
                ) else it
            }
            p[KEY] = list.toJsonString()
        }
    }

    /** Adds a track; no-op if already present. Returns true if newly added. */
    suspend fun addTrack(context: Context, playlistId: Long, trackId: Long): Boolean {
        var added = false
        context.playlistStore.edit { p ->
            val list = parsePlaylistsJson(p[KEY].orEmpty()).map {
                if (it.id == playlistId) {
                    if (trackId in it.trackIds) it
                    else {
                        added = true
                        it.copy(
                            trackIds = it.trackIds + trackId,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                } else it
            }
            p[KEY] = list.toJsonString()
        }
        return added
    }

    suspend fun removeTrack(context: Context, playlistId: Long, trackId: Long) {
        context.playlistStore.edit { p ->
            val list = parsePlaylistsJson(p[KEY].orEmpty()).map {
                if (it.id == playlistId) it.copy(
                    trackIds = it.trackIds.filterNot { id -> id == trackId },
                    updatedAt = System.currentTimeMillis(),
                ) else it
            }
            p[KEY] = list.toJsonString()
        }
    }

    /** Reorder: move trackId from its current index to newIndex (0-based). */
    suspend fun reorder(context: Context, playlistId: Long, newIndex: Int, trackId: Long) {
        context.playlistStore.edit { p ->
            val list = parsePlaylistsJson(p[KEY].orEmpty()).map {
                if (it.id == playlistId) {
                    val filtered = it.trackIds.filterNot { id -> id == trackId }
                    val insertAt = newIndex.coerceIn(0, filtered.size)
                    val reordered = filtered.toMutableList().apply { add(insertAt, trackId) }
                    it.copy(trackIds = reordered, updatedAt = System.currentTimeMillis())
                } else it
            }
            p[KEY] = list.toJsonString()
        }
    }
}
