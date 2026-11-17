package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.libraryTabStore by preferencesDataStore(name = "library_tabs")

object LibraryTabPrefs {
    private val KEY = stringPreferencesKey("tabs")

    fun observe(context: Context): Flow<List<LibraryTab>> =
        context.libraryTabStore.data.map { p ->
            decode(p[KEY]) ?: LibraryTab.defaults()
        }

    suspend fun read(context: Context): List<LibraryTab> = observe(context).first()

    suspend fun save(context: Context, tabs: List<LibraryTab>) {
        context.libraryTabStore.edit { p -> p[KEY] = encode(tabs) }
    }

    suspend fun add(context: Context, tab: LibraryTab) {
        val current = read(context)
        if (current.any { it.id == tab.id }) return
        save(context, current + tab)
    }

    suspend fun rename(context: Context, id: String, label: String) {
        val current = read(context)
        val trimmed = label.trim().ifBlank { return }
        save(context, current.map {
            if (it.id == id) it.copy(label = trimmed) else it
        })
    }

    suspend fun remove(context: Context, id: String) {
        val current = read(context)
        val target = current.firstOrNull { it.id == id } ?: return
        if (target.isDefault && target.kind != LibraryTab.Kind.FAVORITES) return
        save(context, current.filterNot { it.id == id })
    }

    private fun encode(tabs: List<LibraryTab>): String {
        val arr = JSONArray()
        for (t in tabs) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("kind", t.kind.name)
            o.put("label", t.label)
            if (t.playlistId != null) o.put("playlistId", t.playlistId) else o.put("playlistId", JSONObject.NULL)
            o.put("isDefault", t.isDefault)
            if (t.minDurationMs != null) o.put("minDurationMs", t.minDurationMs) else o.put("minDurationMs", JSONObject.NULL)
            if (t.maxDurationMs != null) o.put("maxDurationMs", t.maxDurationMs) else o.put("maxDurationMs", JSONObject.NULL)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<LibraryTab>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<LibraryTab>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = try { LibraryTab.Kind.valueOf(o.getString("kind")) } catch (_: Exception) { continue }
                out.add(
                    LibraryTab(
                        id = o.getString("id"),
                        kind = kind,
                        label = o.getString("label"),
                        playlistId = if (o.isNull("playlistId")) null else o.getLong("playlistId"),
                        isDefault = o.optBoolean("isDefault", false),
                        minDurationMs = if (o.isNull("minDurationMs")) null else o.getLong("minDurationMs"),
                        maxDurationMs = if (o.isNull("maxDurationMs")) null else o.getLong("maxDurationMs"),
                    )
                )
            }
            if (out.isEmpty()) LibraryTab.defaults() else out
        } catch (_: Exception) { null }
    }
}
