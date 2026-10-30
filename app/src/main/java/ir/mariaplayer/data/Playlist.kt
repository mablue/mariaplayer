package ir.mariaplayer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user playlist. Tracks are referenced by Track.id (MediaStore _ID),
 * which is stable as long as the file is not deleted and re-added.
 *
 * JSON is used for storage because org.json ships with Android and
 * avoids adding kotlinx.serialization as a new dependency.
 */
data class Playlist(
    val id: Long,
    val name: String,
    val trackIds: List<Long>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("trackIds", JSONArray(trackIds))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val arr = o.optJSONArray("trackIds") ?: JSONArray()
            val ids = ArrayList<Long>(arr.length())
            for (i in 0 until arr.length()) ids += arr.getLong(i)
            return Playlist(
                id = o.getLong("id"),
                name = o.optString("name", "Untitled"),
                trackIds = ids,
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }
}

internal fun List<Playlist>.toJsonString(): String {
    val arr = JSONArray()
    for (p in this) arr.put(p.toJson())
    return arr.toString()
}

internal fun parsePlaylistsJson(raw: String): List<Playlist> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) out += Playlist.fromJson(arr.getJSONObject(i))
        out
    } catch (_: Exception) {
        emptyList()
    }
}
