package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.libraryDataStore by preferencesDataStore(name = "library")

enum class SortMode {
    TITLE, ARTIST, ALBUM, RECENT, DURATION;

    val label: String
        get() = when (this) {
            TITLE    -> "Title"
            ARTIST   -> "Artist"
            ALBUM    -> "Album"
            RECENT   -> "Recent"
            DURATION -> "Duration"
        }

    companion object {
        fun fromName(n: String?): SortMode =
            entries.firstOrNull { it.name == n } ?: TITLE
    }
}

object LibraryPrefs {
    private val KEY_SORT = stringPreferencesKey("sort")

    suspend fun readSort(context: Context): SortMode {
        val prefs = context.libraryDataStore.data.first()
        return SortMode.fromName(prefs[KEY_SORT])
    }

    suspend fun saveSort(context: Context, mode: SortMode) {
        context.libraryDataStore.edit { it[KEY_SORT] = mode.name }
    }
}
