package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.collectionStore by preferencesDataStore(name = "collections_v2")
private val Context.legacyFavoritesStore by preferencesDataStore(name = "favorites")
private val Context.legacyPlaylistsStore by preferencesDataStore(name = "playlists")

object CollectionRepository {
    private val KEY = stringPreferencesKey("collections_json")

    const val ALL_ID        = "all"
    const val FAVORITES_ID  = "fav"
    const val MUSIC_ID      = "music"
    const val PODCASTS_ID   = "podcasts"
    const val AUDIOBOOKS_ID = "audiobooks"
    const val TRASH_ID      = "trash"

    private val TRASH_KEY = stringSetPreferencesKey("trash_ids")
    private val BLACKLIST_KEY = stringSetPreferencesKey("blacklist_ids")
    private val TRASH_ORIGIN_KEY = stringPreferencesKey("trash_origin_json")

    fun observe(context: Context): Flow<List<Collection>> =
        context.collectionStore.data.map { p ->
            decode(p[KEY].orEmpty()).sortedBy { it.sortOrder }
        }

    suspend fun snapshot(context: Context): List<Collection> = observe(context).first()

    suspend fun initializeIfNeeded(context: Context) {
        val raw = context.collectionStore.data.first()[KEY]
        if (raw != null) return
        val defaults = buildDefaults(migrateLegacy(context))
        context.collectionStore.edit { p -> p[KEY] = encode(defaults) }
    }

    private suspend fun migrateLegacy(context: Context): List<Collection> {
        val out = mutableListOf<Collection>()
        var order = 100
        try {
            val p = context.legacyFavoritesStore.data.first()
            val ids = p[stringSetPreferencesKey("ids")]
                ?.mapNotNull { it.toLongOrNull() }?.toList() ?: emptyList()
            if (ids.isNotEmpty()) {
                out += Collection(FAVORITES_ID, "Favorites",
                    CollectionRule.Manual(ids), sortOrder = 1)
            }
        } catch (_: Exception) {}
        try {
            val p = context.legacyPlaylistsStore.data.first()
            val raw = p[stringPreferencesKey("playlists_json")].orEmpty()
            if (raw.isNotBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getLong("id")
                    val name = o.optString("name", "Untitled")
                    val ids = o.optJSONArray("trackIds") ?: JSONArray()
                    val list = ArrayList<Long>(ids.length())
                    for (j in 0 until ids.length()) list += ids.getLong(j)
                    out += Collection("user_$id", name,
                        CollectionRule.Manual(list), sortOrder = order++,
                        createdAt = o.optLong("createdAt", 0L),
                        updatedAt = o.optLong("updatedAt", 0L))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun buildDefaults(migrated: List<Collection>): List<Collection> {
        val out = mutableListOf<Collection>()
        out += Collection(ALL_ID, "All", CollectionRule.All, isSystem = true, sortOrder = 0)
        migrated.firstOrNull { it.id == FAVORITES_ID }?.let { out += it }
        out += Collection(MUSIC_ID, "Music", CollectionRule.Duration(maxMs = 10 * 60_000L),
            isSystem = true, sortOrder = 10)
        out += Collection(PODCASTS_ID, "Podcasts",
            CollectionRule.Duration(minMs = 10 * 60_000L, maxMs = 2 * 60 * 60_000L),
            isSystem = true, sortOrder = 11)
        out += Collection(AUDIOBOOKS_ID, "Audiobooks",
            CollectionRule.Duration(minMs = 2L * 60 * 60_000L),
            isSystem = true, sortOrder = 12)
        out += Collection(TRASH_ID, "Trash", CollectionRule.All,
            isSystem = true, sortOrder = 999)
        migrated.filter { !it.isSystem && it.id != FAVORITES_ID }.forEach { out += it }
        return out
    }

    suspend fun ensureTrash(context: Context) {
        val raw = context.collectionStore.data.first()[KEY] ?: return
        val list = decode(raw)
        if (list.none { it.id == TRASH_ID }) {
            context.collectionStore.edit { p ->
                p[KEY] = encode(list + Collection(TRASH_ID, "Trash", CollectionRule.All,
                    isSystem = true, sortOrder = 999))
            }
        }
    }

    suspend fun favoriteIds(context: Context): Set<Long> {
        val fav = snapshot(context).firstOrNull { it.id == FAVORITES_ID } ?: return emptySet()
        return (fav.rule as? CollectionRule.Manual)?.trackIds?.toSet() ?: emptySet()
    }

    suspend fun toggleFavorite(context: Context, trackId: Long) {
        context.collectionStore.edit { p ->
            var list = decode(p[KEY].orEmpty())
            val existing = list.firstOrNull { it.id == FAVORITES_ID }
            if (existing == null) {
                list = list + Collection(FAVORITES_ID, "Favorites",
                    CollectionRule.Manual(listOf(trackId)), sortOrder = 1,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis())
            } else {
                val r = existing.rule
                if (r is CollectionRule.Manual) {
                    val next = if (trackId in r.trackIds) r.trackIds - trackId
                               else r.trackIds + trackId
                    list = list.map { if (it.id == FAVORITES_ID)
                        it.copy(rule = CollectionRule.Manual(next),
                                updatedAt = System.currentTimeMillis()) else it }
                }
            }
            p[KEY] = encode(list)
        }
    }

    suspend fun create(context: Context, label: String, rule: CollectionRule): String {
        val id = "user_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        context.collectionStore.edit { p ->
            val list = decode(p[KEY].orEmpty())
            val order = (list.maxOfOrNull { it.sortOrder } ?: 12) + 1
            p[KEY] = encode(list + Collection(id, label.trim().ifBlank { "Untitled" },
                rule, sortOrder = order, createdAt = now, updatedAt = now))
        }
        return id
    }

    suspend fun delete(context: Context, id: String) {
        if (id == ALL_ID || id == TRASH_ID) return
        context.collectionStore.edit { p ->
            p[KEY] = encode(decode(p[KEY].orEmpty()).filterNot { it.id == id })
        }
    }

    suspend fun rename(context: Context, id: String, newLabel: String) {
        val trimmed = newLabel.trim()
        if (trimmed.isBlank()) return
        context.collectionStore.edit { p ->
            p[KEY] = encode(decode(p[KEY].orEmpty()).map {
                if (it.id == id) it.copy(label = trimmed, updatedAt = System.currentTimeMillis()) else it
            })
        }
    }

    suspend fun addTrack(context: Context, id: String, trackId: Long): Boolean {
        var added = false
        context.collectionStore.edit { p ->
            p[KEY] = encode(decode(p[KEY].orEmpty()).map { c ->
                if (c.id != id) c
                else (c.rule as? CollectionRule.Manual)?.let { r ->
                    if (trackId in r.trackIds) c else {
                        added = true
                        c.copy(rule = CollectionRule.Manual(r.trackIds + trackId),
                            updatedAt = System.currentTimeMillis())
                    }
                } ?: c
            })
        }
        return added
    }

    suspend fun removeTrack(context: Context, id: String, trackId: Long) {
        context.collectionStore.edit { p ->
            p[KEY] = encode(decode(p[KEY].orEmpty()).map { c ->
                if (c.id != id) c
                else (c.rule as? CollectionRule.Manual)?.let { r ->
                    c.copy(rule = CollectionRule.Manual(r.trackIds.filterNot { it == trackId }),
                        updatedAt = System.currentTimeMillis())
                } ?: c
            })
        }
    }

    suspend fun reorderTrack(context: Context, id: String, newIndex: Int, trackId: Long) {
        context.collectionStore.edit { p ->
            p[KEY] = encode(decode(p[KEY].orEmpty()).map { c ->
                if (c.id != id) c
                else (c.rule as? CollectionRule.Manual)?.let { r ->
                    if (trackId !in r.trackIds) c else {
                        val filtered = r.trackIds.filterNot { it == trackId }
                        val at = newIndex.coerceIn(0, filtered.size)
                        val next = filtered.toMutableList().apply { add(at, trackId) }
                        c.copy(rule = CollectionRule.Manual(next),
                            updatedAt = System.currentTimeMillis())
                    }
                } ?: c
            })
        }
    }

    fun observeTrashIds(context: Context): Flow<Set<Long>> =
        context.collectionStore.data.map { p ->
            p[TRASH_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        }

    fun observeBlacklistedIds(context: Context): Flow<Set<Long>> =
        context.collectionStore.data.map { p ->
            p[BLACKLIST_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        }

    private fun decodeTrashOrigin(raw: String?): Map<Long, Set<String>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associate { k ->
                val arr = obj.getJSONArray(k)
                val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
                k.toLong() to set
            }
        } catch (_: Exception) { emptyMap() }
    }

    private fun encodeTrashOrigin(map: Map<Long, Set<String>>): String {
        val obj = JSONObject()
        map.forEach { (tid, colls) ->
            if (colls.isNotEmpty()) {
                val arr = JSONArray()
                colls.forEach { arr.put(it) }
                obj.put(tid.toString(), arr)
            }
        }
        return obj.toString()
    }

    suspend fun trashIds(context: Context): Set<Long> = observeTrashIds(context).first()

    suspend fun trash(context: Context, trackId: Long) {
        context.collectionStore.edit { p ->
            val current = p[TRASH_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
            p[TRASH_KEY] = (current + trackId).map { it.toString() }.toSet()
            val decoded = decode(p[KEY].orEmpty())
            val origin = decoded.filter { c ->
                c.id != TRASH_ID &&
                    (c.rule as? CollectionRule.Manual)?.trackIds?.contains(trackId) == true
            }.map { it.id }.toSet()
            val originMap = decodeTrashOrigin(p[TRASH_ORIGIN_KEY]).toMutableMap()
            if (origin.isNotEmpty()) originMap[trackId] = origin
            p[TRASH_ORIGIN_KEY] = encodeTrashOrigin(originMap)
            p[KEY] = encode(decoded.map { c ->
                val r = c.rule
                if (r is CollectionRule.Manual && trackId in r.trackIds) {
                    c.copy(
                        rule = CollectionRule.Manual(r.trackIds.filterNot { it == trackId }),
                        updatedAt = System.currentTimeMillis(),
                    )
                } else c
            })
        }
    }

    suspend fun restore(context: Context, trackId: Long) {
        context.collectionStore.edit { p ->
            val current = p[TRASH_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
            p[TRASH_KEY] = (current - trackId).map { it.toString() }.toSet()
            val originMap = decodeTrashOrigin(p[TRASH_ORIGIN_KEY]).toMutableMap()
            val targets = originMap.remove(trackId) ?: emptySet()
            p[TRASH_ORIGIN_KEY] = encodeTrashOrigin(originMap)
            if (targets.isNotEmpty()) {
                p[KEY] = encode(decode(p[KEY].orEmpty()).map { c ->
                    val r = c.rule
                    if (r is CollectionRule.Manual && c.id in targets && trackId !in r.trackIds) {
                        c.copy(
                            rule = CollectionRule.Manual(r.trackIds + trackId),
                            updatedAt = System.currentTimeMillis(),
                        )
                    } else c
                })
            }
        }
    }

    suspend fun restoreAll(context: Context) {
        context.collectionStore.edit { p ->
            p[TRASH_KEY] = emptySet()
            val originMap = decodeTrashOrigin(p[TRASH_ORIGIN_KEY])
            p[TRASH_ORIGIN_KEY] = ""
            if (originMap.isNotEmpty()) {
                p[KEY] = encode(decode(p[KEY].orEmpty()).map { c ->
                    val r = c.rule
                    if (r is CollectionRule.Manual) {
                        val toAdd = originMap.filter { (_, colls) -> c.id in colls }
                            .keys.filterNot { it in r.trackIds }.toSet()
                        if (toAdd.isNotEmpty()) {
                            c.copy(
                                rule = CollectionRule.Manual(r.trackIds + toAdd),
                                updatedAt = System.currentTimeMillis(),
                            )
                        } else c
                    } else c
                })
            }
        }
    }

    suspend fun emptyTrash(context: Context) {
        context.collectionStore.edit { p ->
            val trashed = p[TRASH_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
            if (trashed.isEmpty()) return@edit
            p[TRASH_KEY] = emptySet()
            p[TRASH_ORIGIN_KEY] = ""
            val currentBlacklist = p[BLACKLIST_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
            p[BLACKLIST_KEY] = (currentBlacklist + trashed).map { it.toString() }.toSet()
            p[KEY] = encode(decode(p[KEY].orEmpty()).map { c ->
                val r = c.rule
                if (r is CollectionRule.Manual) {
                    c.copy(
                        rule = CollectionRule.Manual(r.trackIds.filterNot { it in trashed }),
                        updatedAt = System.currentTimeMillis(),
                    )
                } else c
            })
        }
    }

    suspend fun permanentDelete(context: Context, trackId: Long) {
        context.collectionStore.edit { p ->
            val trashed = p[TRASH_KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
            if (trackId in trashed) {
                p[TRASH_KEY] = (trashed - trackId).map { it.toString() }.toSet()
            }
            val originMap = decodeTrashOrigin(p[TRASH_ORIGIN_KEY]).toMutableMap()
            if (originMap.remove(trackId) != null) {
                p[TRASH_ORIGIN_KEY] = encodeTrashOrigin(originMap)
            }
            p[KEY] = encode(decode(p[KEY].orEmpty()).map { c ->
                val r = c.rule
                if (r is CollectionRule.Manual && trackId in r.trackIds) {
                    c.copy(
                        rule = CollectionRule.Manual(r.trackIds.filterNot { it == trackId }),
                        updatedAt = System.currentTimeMillis(),
                    )
                } else c
            })
        }
    }

    fun resolveTracks(
        allTracks: List<Track>,
        collection: Collection,
        trashedIds: Set<Long> = emptySet(),
        blacklistedIds: Set<Long> = emptySet(),
        excludeIds: Set<Long> = emptySet(),
    ): List<Track> {
        // Trash همیشه نمای Trash است — نه فیلتر جهانی.
        if (collection.id == TRASH_ID) {
            return allTracks.filter { it.id in trashedIds && it.id !in blacklistedIds }
        }
        val base = when (val r = collection.rule) {
            is CollectionRule.All -> allTracks
            is CollectionRule.Manual -> {
                val byId = allTracks.associateBy { it.id }
                r.trackIds.mapNotNull { byId[it] }
            }
            else -> allTracks.filter { r.matches(it) }
        }
        // excludeIds = ترک‌هایی که در collection دیگری با excludeFromOthers=true هستند.
        // خود collectionِ partition از exclusion معاف است (collection.id نباید در excludeIds باشد).
        return base.filter {
            it.id !in trashedIds &&
            it.id !in blacklistedIds &&
            it.id !in excludeIds
        }
    }

    /**
     * ترک‌هایی که باید از هر collection دیگری مخفی شوند — یعنی اجتماعِ
     * collectionهایی که excludeFromOthers = true دارند.
     * (trash و blacklist همیشه مستقل‌اند.)
     */
    fun computeExcludedIds(
        allTracks: List<Track>,
        collections: List<Collection>,
        trashedIds: Set<Long> = emptySet(),
        blacklistedIds: Set<Long> = emptySet(),
    ): Set<Long> {
        val partitions = collections.filter {
            it.excludeFromOthers && it.id != TRASH_ID && it.id != ALL_ID
        }
        if (partitions.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        for (c in partitions) {
            val base = when (val r = c.rule) {
                is CollectionRule.All -> allTracks
                is CollectionRule.Manual -> {
                    val byId = allTracks.associateBy { it.id }
                    r.trackIds.mapNotNull { byId[it] }
                }
                else -> allTracks.filter { r.matches(it) }
            }
            for (t in base) {
                if (t.id !in trashedIds && t.id !in blacklistedIds) out += t.id
            }
        }
        return out
    }

    private fun encode(list: List<Collection>): String {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().apply {
            put("id", c.id); put("label", c.label)
            put("isSystem", c.isSystem); put("sortOrder", c.sortOrder)
            put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
            put("excludeFromOthers", c.excludeFromOthers)
            put("rule", encodeRule(c.rule))
        })
        return arr.toString()
    }

    private fun encodeRule(rule: CollectionRule): JSONObject = JSONObject().apply {
        when (rule) {
            is CollectionRule.All -> put("type", "all")
            is CollectionRule.Manual -> { put("type", "manual"); put("trackIds", JSONArray(rule.trackIds)) }
            is CollectionRule.Duration -> {
                put("type", "duration")
                if (rule.minMs == null) put("minMs", JSONObject.NULL) else put("minMs", rule.minMs)
                if (rule.maxMs == null) put("maxMs", JSONObject.NULL) else put("maxMs", rule.maxMs)
            }
            is CollectionRule.Types -> {
                put("type", "types")
                put("types", JSONArray(rule.types.map { it.name }))
            }
            is CollectionRule.Pattern -> {
                put("type", "pattern")
                put("pattern", rule.pattern)
                put("fields", JSONArray(rule.fields.map { it.name }))
            }
            is CollectionRule.And -> {
                put("type", "and")
                put("rules", JSONArray(rule.rules.map { encodeRule(it) }))
            }
            is CollectionRule.Or -> {
                put("type", "or")
                put("rules", JSONArray(rule.rules.map { encodeRule(it) }))
            }
            is CollectionRule.Not -> {
                put("type", "not")
                put("rule", encodeRule(rule.rule))
            }
        }
    }

    private fun decode(raw: String): List<Collection> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Collection>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Collection(
                    id = o.getString("id"),
                    label = o.optString("label", "Untitled"),
                    isSystem = o.optBoolean("isSystem", false),
                    sortOrder = o.optInt("sortOrder", 0),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    excludeFromOthers = o.optBoolean("excludeFromOthers", false),
                    rule = decodeRule(o.getJSONObject("rule")),
                )
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun decodeRule(o: JSONObject): CollectionRule = when (o.optString("type")) {
        "all" -> CollectionRule.All
        "manual" -> {
            val ids = o.optJSONArray("trackIds") ?: JSONArray()
            val list = ArrayList<Long>(ids.length())
            for (i in 0 until ids.length()) list += ids.getLong(i)
            CollectionRule.Manual(list)
        }
        "duration" -> CollectionRule.Duration(
            minMs = if (o.isNull("minMs")) null else o.getLong("minMs"),
            maxMs = if (o.isNull("maxMs")) null else o.getLong("maxMs"))
        "types" -> {
            val arr = o.optJSONArray("types") ?: JSONArray()
            val set = mutableSetOf<ContentType>()
            for (i in 0 until arr.length()) {
                try { set += ContentType.valueOf(arr.getString(i)) } catch (_: Exception) {}
            }
            CollectionRule.Types(set)
        }
        "pattern" -> {
            val arr = o.optJSONArray("fields") ?: JSONArray()
            val set = mutableSetOf<CollectionRule.Pattern.Field>()
            for (i in 0 until arr.length()) {
                try { set += CollectionRule.Pattern.Field.valueOf(arr.getString(i)) } catch (_: Exception) {}
            }
            CollectionRule.Pattern(
                pattern = o.optString("pattern", ""),
                fields = if (set.isEmpty()) CollectionRule.Pattern.Field.entries.toSet() else set)
        }
        "and" -> {
            val arr = o.optJSONArray("rules") ?: JSONArray()
            val list = ArrayList<CollectionRule>(arr.length())
            for (i in 0 until arr.length()) {
                try { list += decodeRule(arr.getJSONObject(i)) } catch (_: Exception) {}
            }
            CollectionRule.And(list)
        }
        "or" -> {
            val arr = o.optJSONArray("rules") ?: JSONArray()
            val list = ArrayList<CollectionRule>(arr.length())
            for (i in 0 until arr.length()) {
                try { list += decodeRule(arr.getJSONObject(i)) } catch (_: Exception) {}
            }
            CollectionRule.Or(list)
        }
        "not" -> {
            val inner = o.optJSONObject("rule")
            if (inner == null) CollectionRule.All else CollectionRule.Not(decodeRule(inner))
        }
        else -> CollectionRule.All
    }
}
