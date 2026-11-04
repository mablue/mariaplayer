package ir.mariaplayer.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.displayStore by preferencesDataStore(name = "lyrics_display")

data class DisplayPrefs(
    val fontSize: Int = 22,
    val activeFontSize: Int = 34,
    val colorActive: Long = 0xFF7928F5,
    val colorInactive: Long = 0xFFB0B0B0,
    val colorBackground: Long = 0xFF0A0A0F,
)

object LyricsDisplayPrefs {
    private val KEY_FONT = intPreferencesKey("font_size")
    private val KEY_ACTIVE_FONT = intPreferencesKey("active_font_size")
    private val KEY_COLOR_A = longPreferencesKey("color_active")
    private val KEY_COLOR_I = longPreferencesKey("color_inactive")
    private val KEY_COLOR_BG = longPreferencesKey("color_background")

    fun observe(context: Context): Flow<DisplayPrefs> = context.displayStore.data.map { p ->
        DisplayPrefs(
            fontSize = p[KEY_FONT] ?: 22,
            activeFontSize = p[KEY_ACTIVE_FONT] ?: 34,
            colorActive = p[KEY_COLOR_A] ?: 0xFF7928F5,
            colorInactive = p[KEY_COLOR_I] ?: 0xFFB0B0B0,
            colorBackground = p[KEY_COLOR_BG] ?: 0xFF0A0A0F,
        )
    }

    suspend fun read(context: Context): DisplayPrefs = observe(context).first()

    suspend fun save(context: Context, prefs: DisplayPrefs) {
        context.displayStore.edit { p ->
            p[KEY_FONT] = prefs.fontSize
            p[KEY_ACTIVE_FONT] = prefs.activeFontSize
            p[KEY_COLOR_A] = prefs.colorActive
            p[KEY_COLOR_I] = prefs.colorInactive
            p[KEY_COLOR_BG] = prefs.colorBackground
        }
    }
}
