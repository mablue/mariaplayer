package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favStore by preferencesDataStore(name = "favorites")

object FavoritesPrefs {
    private val KEY = stringSetPreferencesKey("ids")

    fun observe(context: Context): Flow<Set<Long>> =
        context.favStore.data.map { p ->
            p[KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        }

    suspend fun toggle(context: Context, id: Long) {
        context.favStore.edit { p ->
            val cur = (p[KEY] ?: emptySet()).toMutableSet()
            val s = id.toString()
            if (s in cur) cur.remove(s) else cur.add(s)
            p[KEY] = cur
        }
    }
}
