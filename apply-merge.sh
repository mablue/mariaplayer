#!/data/data/com.termux/files/usr/bin/bash
# MariaPlayer – merge Favorites+Playlists+Tabs → CollectionRepository (v2)
set -Eeuo pipefail
BASE="$(pwd)"; cd "$BASE"
ORIG_SHA=$(git rev-parse HEAD)
log()  { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { log "❌ $*"; exit 1; }
trap 'rc=$?; if [ $rc -ne 0 ]; then log "⚠️  rollback → $ORIG_SHA"; git reset --hard "$ORIG_SHA" >/dev/null 2>&1 || true; fi' EXIT

[[ -d .git ]] || fail "no .git"
git diff --quiet && git diff --cached --quiet || fail "dirty tree — commit/stash first"
command -v python3 >/dev/null || fail "python3 needed"

# ── 1) Collection.kt ──────────────────────────────────
log "1/6 data/Collection.kt"
cat > app/src/main/java/ir/mariaplayer/data/Collection.kt <<'EOF'
package ir.mariaplayer.data

data class Collection(
    val id: String,
    val label: String,
    val rule: CollectionRule,
    val isSystem: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

sealed interface CollectionRule {
    fun matches(track: Track): Boolean

    object All : CollectionRule {
        override fun matches(track: Track) = true
    }

    data class Manual(val trackIds: List<Long>) : CollectionRule {
        override fun matches(track: Track) = track.id in trackIds
    }

    data class Duration(val minMs: Long? = null, val maxMs: Long? = null) : CollectionRule {
        override fun matches(track: Track) =
            (minMs == null || track.durationMs >= minMs) &&
            (maxMs == null || track.durationMs <= maxMs)
    }

    data class Types(val types: Set<ContentType>) : CollectionRule {
        override fun matches(track: Track) = track.contentType in types
    }

    data class Pattern(
        val pattern: String,
        val fields: Set<Field> = Field.entries.toSet(),
    ) : CollectionRule {
        enum class Field { Title, Album, Folder, Filename }
        override fun matches(track: Track): Boolean {
            val re = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { return false }
            return fields.any { f ->
                val v = when (f) {
                    Field.Title -> track.title
                    Field.Album -> track.album ?: ""
                    Field.Folder -> track.folder
                    Field.Filename -> track.filename
                }
                re.containsMatchIn(v)
            }
        }
    }
}
EOF

# ── 2) CollectionRepository.kt ────────────────────────
log "2/6 data/CollectionRepository.kt"
cat > app/src/main/java/ir/mariaplayer/data/CollectionRepository.kt <<'EOF'
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
        migrated.filter { !it.isSystem && it.id != FAVORITES_ID }.forEach { out += it }
        return out
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
        if (id == ALL_ID) return
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

    fun resolveTracks(allTracks: List<Track>, collection: Collection): List<Track> =
        when (val r = collection.rule) {
            is CollectionRule.All -> allTracks
            is CollectionRule.Manual -> {
                val byId = allTracks.associateBy { it.id }
                r.trackIds.mapNotNull { byId[it] }
            }
            else -> allTracks.filter { r.matches(it) }
        }

    private fun encode(list: List<Collection>): String {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().apply {
            put("id", c.id); put("label", c.label)
            put("isSystem", c.isSystem); put("sortOrder", c.sortOrder)
            put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
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
        else -> CollectionRule.All
    }
}
EOF

# ── 3) CollectionBar.kt ───────────────────────────────
log "3/6 ui/CollectionBar.kt"
cat > app/src/main/java/ir/mariaplayer/ui/CollectionBar.kt <<'EOF'
package ir.mariaplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.mariaplayer.R
import ir.mariaplayer.data.Collection

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionBar(
    collections: List<Collection>,
    selectedId: String?,
    onSelect: (Collection) -> Unit,
    onAdd: () -> Unit,
    onLongPress: (Collection) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(collections, key = { it.id }) { c ->
            val selected = c.id == selectedId
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .combinedClickable(
                        onClick = { onSelect(c) },
                        onLongClick = { onLongPress(c) },
                    ),
            ) {
                Text(
                    text = c.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        item {
            IconButton(onClick = onAdd) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "New collection",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
EOF

# ── 4) AddToPlaylistSheet.kt ──────────────────────────
log "4/6 ui/AddToPlaylistSheet.kt"
cat > app/src/main/java/ir/mariaplayer/ui/AddToPlaylistSheet.kt <<'EOF'
package ir.mariaplayer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.mariaplayer.data.Collection
import ir.mariaplayer.data.CollectionRepository
import ir.mariaplayer.data.CollectionRule
import ir.mariaplayer.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    tracks: List<Track>,
    workScope: CoroutineScope? = null,
    onDismiss: () -> Unit,
) {
    if (tracks.isEmpty()) { onDismiss(); return }
    val context = LocalContext.current
    val localScope = rememberCoroutineScope()
    val scope = workScope ?: localScope
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val collections by produceState<List<Collection>>(initialValue = emptyList()) {
        CollectionRepository.observe(context).collectLatest { value = it }
    }
    val targets = collections.filter { it.rule is CollectionRule.Manual }

    var newNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                if (tracks.size == 1) "Add to playlist" else "Add ${tracks.size} tracks to playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (tracks.size == 1) {
                Text(tracks[0].title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(targets, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.label) },
                        supportingContent = {
                            val n = (c.rule as? CollectionRule.Manual)?.trackIds?.size ?: 0
                            Text("$n tracks")
                        },
                        modifier = Modifier.clickable {
                            scope.launch {
                                var added = 0; var dup = 0
                                for (t in tracks) {
                                    if (CollectionRepository.addTrack(context, c.id, t.id)) added++ else dup++
                                }
                                val msg = when {
                                    added == 0 -> "Already in ${c.label}"
                                    dup == 0 -> if (tracks.size == 1) "Added to ${c.label}" else "Added $added to ${c.label}"
                                    else -> "Added $added, $dup already in ${c.label}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        },
                    )
                    HorizontalDivider()
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text("+ New playlist", color = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { newName = ""; newNameDialog = true },
                    )
                }
                item { Column(Modifier.padding(top = 16.dp)) {} }
            }
        }
    }

    if (newNameDialog) {
        AlertDialog(
            onDismissRequest = { newNameDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    singleLine = true, placeholder = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val name = newName.trim()
                        scope.launch {
                            val id = CollectionRepository.create(
                                context, name, CollectionRule.Manual(emptyList()))
                            for (t in tracks) CollectionRepository.addTrack(context, id, t.id)
                            Toast.makeText(context,
                                if (tracks.size == 1) "Created $name and added track"
                                else "Created $name with ${tracks.size} tracks",
                                Toast.LENGTH_SHORT).show()
                        }
                        newNameDialog = false
                        onDismiss()
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { newNameDialog = false }) { Text("Cancel") }
            },
        )
    }
}
EOF

# ── 5) Patch MainActivity.kt ──────────────────────────
log "5/6 patch MainActivity.kt"
python3 <<'PYEOF'
import pathlib, sys, re
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
t = p.read_text(encoding="utf-8")

def must(search, label):
    if search not in t: raise Exception(f"[{label}] search not found:\n{search[:250]}")

def sub(search, replacement, label):
    global t
    must(search, label)
    t = t.replace(search, replacement, 1)

def replace_block(start_marker, replacement, label):
    global t
    idx = t.find(start_marker)
    if idx < 0: raise Exception(f"[{label}] start marker not found")
    depth = 0; started = False; i = idx
    while i < len(t):
        if t[i] == '{': depth += 1; started = True
        elif t[i] == '}':
            depth -= 1
            if started and depth == 0:
                t = t[:idx] + replacement + t[i+1:]
                return
        i += 1
    raise Exception(f"[{label}] no matching brace")

# ── imports ──
sub("import ir.mariaplayer.data.FavoritesPrefs\n",
    "import ir.mariaplayer.data.Collection\n"
    "import ir.mariaplayer.data.CollectionRepository\n"
    "import ir.mariaplayer.data.CollectionRule\n",
    "imports-data")
sub("import ir.mariaplayer.ui.PlaylistsScreen\n", "", "imports-ps")
sub("import ir.mariaplayer.ui.PlaylistDetailScreen\n", "", "imports-pds")

# ── state block (favorites + tabs → collections) ──
sub("""    val favorites by produceState<Set<Long>>(initialValue = emptySet()) {
        FavoritesPrefs.observe(context).collectLatest { value = it }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var folderFilter by rememberSaveable { mutableStateOf("") }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    var tabs by remember { mutableStateOf(ir.mariaplayer.data.LibraryTab.defaults()) }
    var selectedTabId by rememberSaveable { mutableStateOf("all") }
    val selectedTab = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.first()
    var showNewTabDialog by remember { mutableStateOf(false) }
    var tabToEdit by remember { mutableStateOf<ir.mariaplayer.data.LibraryTab?>(null) }
    LaunchedEffect(Unit) {
        tabs = ir.mariaplayer.data.LibraryTabPrefs.read(context)
    }""",
"""    var collections by remember { mutableStateOf<List<Collection>>(emptyList()) }
    var selectedCollectionId by rememberSaveable { mutableStateOf(CollectionRepository.ALL_ID) }
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    var collectionToEdit by remember { mutableStateOf<Collection?>(null) }
    LaunchedEffect(Unit) {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.observe(context).collectLatest { collections = it }
    }
    val selectedCollection = remember(collections, selectedCollectionId) {
        collections.firstOrNull { it.id == selectedCollectionId } ?: collections.firstOrNull()
    }
    val favorites: Set<Long> = remember(collections) {
        (collections.firstOrNull { it.id == CollectionRepository.FAVORITES_ID }?.rule as? CollectionRule.Manual)
            ?.trackIds?.toSet() ?: emptySet()
    }

    var query by rememberSaveable { mutableStateOf("") }
    var folderFilter by rememberSaveable { mutableStateOf("") }""",
    "state-block")

# ── visible filter ──
sub("""    val visible: List<Track>? = remember(tracks, query, sortMode, folderFilter, favOnly, favorites, selectedTab) {
        val base = tracks ?: return@remember null
        val q = query.trim().lowercase()
        base.asSequence()
            .filter { t ->
                (folderFilter.isEmpty() || t.folder == folderFilter) &&
                (!favOnly || t.id in favorites) &&
                tabMatches(selectedTab, t, favorites) &&
                (q.isEmpty() ||
                    t.title.lowercase().contains(q) ||
                    t.artist?.lowercase()?.contains(q) == true ||
                    t.album?.lowercase()?.contains(q) == true)
            }
            .sortedWith(comparatorFor(sortMode))
            .toList()
    }""",
"""    val visible: List<Track>? = remember(tracks, query, sortMode, folderFilter, selectedCollection) {
        val base = tracks ?: return@remember null
        val inCollection = selectedCollection?.let { CollectionRepository.resolveTracks(base, it) } ?: base
        val q = query.trim().lowercase()
        inCollection.asSequence()
            .filter { t ->
                (folderFilter.isEmpty() || t.folder == folderFilter) &&
                (q.isEmpty() ||
                    t.title.lowercase().contains(q) ||
                    t.artist?.lowercase()?.contains(q) == true ||
                    t.album?.lowercase()?.contains(q) == true)
            }
            .sortedWith(comparatorFor(sortMode))
            .toList()
    }""",
    "visible-filter")

# ── tabMatches helper removal ──
sub("""private fun tabMatches(
    tab: ir.mariaplayer.data.LibraryTab,
    t: Track,
    favorites: Set<Long>,
): Boolean = tab.matches(t, favorites)
""", "", "tabMatches")

# ── new-tab dialog → NewCollectionDialog call ──
replace_block("    if (showNewTabDialog) {",
"""    if (showNewCollectionDialog) {
        NewCollectionDialog(
            onDismiss = { showNewCollectionDialog = false },
            onCreate = { name, rule ->
                scope.launch {
                    val id = CollectionRepository.create(context, name, rule)
                    selectedCollectionId = id
                }
                showNewCollectionDialog = false
            },
        )
    }""", "new-dialog")

# ── edit-tab dialog → EditCollectionDialog call ──
replace_block("    tabToEdit?.let { tab ->",
"""    collectionToEdit?.let { coll ->
        EditCollectionDialog(
            collection = coll,
            onDismiss = { collectionToEdit = null },
            onSave = { newName ->
                scope.launch { CollectionRepository.rename(context, coll.id, newName) }
                collectionToEdit = null
            },
            onDelete = {
                scope.launch {
                    CollectionRepository.delete(context, coll.id)
                    if (selectedCollectionId == coll.id) selectedCollectionId = CollectionRepository.ALL_ID
                }
                collectionToEdit = null
            },
        )
    }""", "edit-dialog")

# ── remove playlists routes ──
replace_block('        screen == "playlists" -> {', "", "route-playlists")
replace_block('        screen.startsWith("playlist:") -> {', "", "route-playlist-detail")

# ── BackHandler ──
sub("""        screen = when {
            screen.startsWith("playlist:") -> "playlists"
            else -> "library"
        }""",
    '        screen = "library"', "backhandler")

# ── onToggleFavorite ──
sub("""        onToggleFavorite = { id -> scope.launch {
            val wasFav = id in favorites
            FavoritesPrefs.toggle(context, id)
            if (!wasFav) {
                val current = ir.mariaplayer.data.LibraryTabPrefs.read(context)
                if (current.none { it.kind == ir.mariaplayer.data.LibraryTab.Kind.FAVORITES }) {
                    ir.mariaplayer.data.LibraryTabPrefs.save(
                        context,
                        current + ir.mariaplayer.data.LibraryTab.favorites(),
                    )
                    tabs = ir.mariaplayer.data.LibraryTabPrefs.read(context)
                }
            }
        }},""",
    "        onToggleFavorite = { id -> scope.launch { CollectionRepository.toggleFavorite(context, id) } },",
    "onToggleFavorite")

# ── shuffle pool ──
sub("""            val pool = when (selectedTabId) {
                "all" -> list
                "music" -> list.filter { it.contentType == ir.mariaplayer.data.ContentType.MUSIC }
                "podcasts" -> list.filter { it.contentType == ir.mariaplayer.data.ContentType.PODCAST }
                "audiobooks" -> list.filter { it.contentType == ir.mariaplayer.data.ContentType.AUDIOBOOK }
                else -> list
            }""",
    "            val pool = list", "shuffle")

# ── call site param removals ──
sub("        favOnly = favOnly,\n        onFavOnlyChange = { favOnly = it },\n", "", "call-favOnly")
sub("        onPlaylistsClick = { screen = \"playlists\" },\n", "", "call-onPlaylistsClick")
sub("""        tabs = tabs,
        selectedTabId = selectedTabId,
        onTabSelect = { selectedTabId = it },
        onTabAdd = { showNewTabDialog = true },
        onTabLongPress = { tab -> tabToEdit = tab },""",
"""        collections = collections,
        selectedCollectionId = selectedCollection?.id ?: "",
        onSelectCollection = { selectedCollectionId = it.id },
        onAddCollection = { showNewCollectionDialog = true },
        onEditCollection = { collectionToEdit = it },""",
    "call-tabs")

# ── signature params ──
sub("    favOnly: Boolean,\n    onFavOnlyChange: (Boolean) -> Unit,\n", "", "sig-favOnly")
sub("    onPlaylistsClick: () -> Unit,\n", "", "sig-onPlaylistsClick")
sub("""    tabs: List<ir.mariaplayer.data.LibraryTab>,
    selectedTabId: String,
    onTabSelect: (String) -> Unit,
    onTabAdd: () -> Unit,
    onTabLongPress: (ir.mariaplayer.data.LibraryTab) -> Unit,
) {""",
"""    collections: List<Collection>,
    selectedCollectionId: String,
    onSelectCollection: (Collection) -> Unit,
    onAddCollection: () -> Unit,
    onEditCollection: (Collection) -> Unit,
) {""", "sig-tabs")

# ── LibraryTabBar call → CollectionBar ──
sub("""            ir.mariaplayer.ui.LibraryTabBar(
                tabs = tabs,
                selectedId = selectedTabId,
                onSelect = onTabSelect,
                onAdd = onTabAdd,
                onLongPress = onTabLongPress,
            )""",
"""            ir.mariaplayer.ui.CollectionBar(
                collections = collections,
                selectedId = selectedCollectionId,
                onSelect = onSelectCollection,
                onAdd = onAddCollection,
                onLongPress = onEditCollection,
            )""", "bar-call")

# ── toolbar playlists button ──
sub("""                    IconButton(onClick = { onPlaylistsClick() }, modifier = sz) {
                        Icon(painter = painterResource(R.drawable.ic_playlist),
                            contentDescription = "Playlists", modifier = isz)
                    }
""", "", "toolbar-playlists")

# ── empty state block (INCLUDES isFavTab line from v3) ──
sub("""                        val isFavTab = tabs.firstOrNull { it.id == selectedTabId }?.kind ==
                            ir.mariaplayer.data.LibraryTab.Kind.FAVORITES
                        val msg = when {
                            allTracks.isNullOrEmpty() ->
                                "No audio files found.\\nAdd music to /sdcard/Music, then reopen the app."
                            query.isNotBlank() -> "No results for \\"$query\\""
                            isFavTab -> "No favorites yet.\\nTap the heart next to a track to add."
                            favOnly -> "No favorites yet.\\nTap the heart next to a track to add."
                            folderFilter.isNotEmpty() -> "No tracks in \\"$folderFilter\\""
                            else -> "No tracks match the current filters."
                        }""",
"""                        val isFavTab = selectedCollectionId == CollectionRepository.FAVORITES_ID
                        val msg = when {
                            allTracks.isNullOrEmpty() ->
                                "No audio files found.\\nAdd music to /sdcard/Music, then reopen the app."
                            query.isNotBlank() -> "No results for \\"$query\\""
                            isFavTab -> "No favorites yet.\\nTap the heart next to a track to add."
                            folderFilter.isNotEmpty() -> "No tracks in \\"$folderFilter\\""
                            else -> "No tracks match the current filters."
                        }""", "empty-state")

# ── append new dialogs ──
t += '''

@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
private fun NewCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (String, CollectionRule) -> Unit,
) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var mode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("manual") }
    var regexPattern by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New collection") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                Text("Type:", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "manual" to "Manual",
                        "under10" to "<10m",
                        "10m2h" to "10m-2h",
                        "over2h" to "2h+",
                        "regex" to "Regex",
                    ).forEach { (m, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = mode == m, onClick = { mode = m },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                if (mode == "regex") {
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = regexPattern, onValueChange = { regexPattern = it },
                        singleLine = true, label = { Text("Pattern") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = name.isNotBlank() && (mode != "regex" || regexPattern.isNotBlank()),
                onClick = {
                    val rule: CollectionRule = when (mode) {
                        "manual" -> CollectionRule.Manual(emptyList())
                        "under10" -> CollectionRule.Duration(maxMs = 10 * 60_000L)
                        "10m2h" -> CollectionRule.Duration(minMs = 10 * 60_000L, maxMs = 2 * 60 * 60_000L)
                        "over2h" -> CollectionRule.Duration(minMs = 2L * 60 * 60_000L)
                        "regex" -> CollectionRule.Pattern(regexPattern)
                        else -> CollectionRule.Manual(emptyList())
                    }
                    onCreate(name.trim(), rule)
                },
            ) { Text("Create") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
private fun EditCollectionDialog(
    collection: Collection,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editedName by androidx.compose.runtime.remember(collection.id) {
        androidx.compose.runtime.mutableStateOf(collection.label)
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit collection") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = editedName, onValueChange = { editedName = it },
                    singleLine = true, label = { Text("Name") },
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                Text(
                    "Rule: ${describeCollectionRule(collection.rule)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = editedName.isNotBlank(),
                onClick = { onSave(editedName.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (collection.id != CollectionRepository.ALL_ID) {
                    androidx.compose.material3.TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun describeCollectionRule(rule: CollectionRule): String = when (rule) {
    is CollectionRule.All -> "All tracks"
    is CollectionRule.Manual -> "${rule.trackIds.size} tracks"
    is CollectionRule.Duration -> {
        val min = rule.minMs?.let { "${it / 60_000}m" } ?: "0"
        val max = rule.maxMs?.let { "${it / 60_000}m" } ?: "inf"
        "Duration $min to $max"
    }
    is CollectionRule.Types -> "Types: ${rule.types.joinToString { it.name }}"
    is CollectionRule.Pattern -> "Regex: ${rule.pattern}"
}
'''

# ── sanity check ──
stale_patterns = [
    r'\bfavOnly\b', r'\btabs\b', r'\bselectedTabId\b', r'\bselectedTab\b',
    r'\bshowNewTabDialog\b', r'\btabToEdit\b',
    r'\bLibraryTab\b', r'\bLibraryTabBar\b', r'\bLibraryTabPrefs\b',
    r'\bFavoritesPrefs\b', r'\bPlaylistPrefs\b',
    r'\bPlaylistsScreen\b', r'\bPlaylistDetailScreen\b',
    r'\btabMatches\b', r'\bonTabSelect\b', r'\bonTabAdd\b',
    r'\bonTabLongPress\b', r'\bonPlaylistsClick\b',
    r'\bonFavOnlyChange\b', r'\bonOpenPlaylist\b',
]
stale = []
for pat in stale_patterns:
    for m in re.finditer(pat, t):
        line = t[:m.start()].count('\n') + 1
        ctx = t[max(0, m.start()-40):m.end()+40].replace('\n', ' ')
        stale.append((pat, line, ctx))
if stale:
    print(f"STALE refs found: {len(stale)}")
    for pat, line, ctx in stale[:8]:
        print(f"  {pat} L{line}: {ctx}")
    sys.exit(2)

p.write_text(t, encoding="utf-8")
print("MainActivity OK (no stale refs)")
PYEOF

# ── 6) Patch SelfTestScreen.kt ────────────────────────
log "6/6 patch SelfTestScreen.kt"
python3 <<'PYEOF'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/ui/SelfTestScreen.kt")
t = p.read_text(encoding="utf-8")

for imp in [
    "import ir.mariaplayer.data.FavoritesPrefs\n",
    "import ir.mariaplayer.data.LibraryTab\n",
    "import ir.mariaplayer.data.LibraryTabPrefs\n",
    "import ir.mariaplayer.data.PlaylistPrefs\n",
]:
    t = t.replace(imp, "")

if "import ir.mariaplayer.data.ContentType\n" not in t:
    raise Exception("ContentType import anchor missing")
t = t.replace(
    "import ir.mariaplayer.data.ContentType\n",
    "import ir.mariaplayer.data.Collection\n"
    "import ir.mariaplayer.data.CollectionRepository\n"
    "import ir.mariaplayer.data.CollectionRule\n"
    "import ir.mariaplayer.data.ContentType\n",
    1,
)

def remove_test(text, name):
    marker = f'    test("{name}") {{'
    idx = text.find(marker)
    if idx < 0: return text, False
    depth = 0; started = False; i = idx
    while i < len(text):
        if text[i] == '{': depth += 1; started = True
        elif text[i] == '}':
            depth -= 1
            if started and depth == 0:
                end = i + 1
                if end < len(text) and text[end] == '\n': end += 1
                return text[:idx] + text[end:], True
        i += 1
    return text, False

removed = 0
for name in [
    "Favorites round-trip", "Playlist CRUD",
    "Tabs: defaults have 4", "Tabs: defaults have 5",
    "Tabs: defaults all isDefault",
    "Tabs: add custom round-trip", "Tabs: remove custom works",
    "Tabs: default cannot be removed", "Tabs: rename round-trip",
    "Tabs: empty prefs \u2192 defaults",
    "Tabs: filter predicate ALL kind",
    "Tabs: add same id twice = no dup",
    "Tabs: multiple customs preserved in order",
    "Tabs: remove middle keeps neighbors",
    "Tabs: rename does not touch siblings",
    "Tabs: default ids stable", "Tabs: PLAYLIST kind semantics",
    "Tabs: FAVORITES is default", "Tabs: FAVORITES can be removed",
    "Tabs: SMART round-trip",
    "Tab.matches: FAVORITES", "Tab.matches: SMART <10m",
    "Tab.matches: SMART >=10m", "Tab.matches: SMART >=2h",
]:
    t, ok = remove_test(t, name)
    if ok: removed += 1
print(f"removed {removed} obsolete tests")

new_tests = '''
    test("Collection: defaults after init") {
        CollectionRepository.initializeIfNeeded(context)
        val colls = CollectionRepository.snapshot(context)
        if (colls.none { it.id == CollectionRepository.ALL_ID }) throw IllegalStateException("All")
        if (colls.none { it.id == CollectionRepository.MUSIC_ID }) throw IllegalStateException("Music")
        if (colls.none { it.id == CollectionRepository.PODCASTS_ID }) throw IllegalStateException("Podcasts")
        if (colls.none { it.id == CollectionRepository.AUDIOBOOKS_ID }) throw IllegalStateException("Audiobooks")
        "${colls.size} collections"
    }
    test("Collection: All is first") {
        CollectionRepository.initializeIfNeeded(context)
        val colls = CollectionRepository.snapshot(context)
        if (colls.firstOrNull()?.id != CollectionRepository.ALL_ID)
            throw IllegalStateException("first=${colls.firstOrNull()?.id}")
        "ok"
    }
    test("Collection: All cannot be deleted") {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.delete(context, CollectionRepository.ALL_ID)
        val colls = CollectionRepository.snapshot(context)
        if (colls.none { it.id == CollectionRepository.ALL_ID })
            throw IllegalStateException("All was deleted")
        "protected"
    }
    test("Collection: Favorites round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val testId = 9_900_000_000L + Random.nextLong(100_000)
        val before = testId in CollectionRepository.favoriteIds(context)
        CollectionRepository.toggleFavorite(context, testId)
        val added = testId in CollectionRepository.favoriteIds(context)
        CollectionRepository.toggleFavorite(context, testId)
        val removed = testId !in CollectionRepository.favoriteIds(context)
        if (before || !added || !removed)
            throw IllegalStateException("before=$before add=$added rm=$removed")
        "ok"
    }
    test("Collection: Favorites auto-created on toggle") {
        CollectionRepository.initializeIfNeeded(context)
        CollectionRepository.delete(context, CollectionRepository.FAVORITES_ID)
        val testId = 9_900_001_000L + Random.nextLong(100_000)
        CollectionRepository.toggleFavorite(context, testId)
        val created = CollectionRepository.snapshot(context)
            .any { it.id == CollectionRepository.FAVORITES_ID }
        CollectionRepository.toggleFavorite(context, testId)
        CollectionRepository.delete(context, CollectionRepository.FAVORITES_ID)
        if (!created) throw IllegalStateException("fav not created")
        "ok"
    }
    test("Collection: Manual CRUD") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "T_"+System.currentTimeMillis(),
            CollectionRule.Manual(emptyList()))
        CollectionRepository.addTrack(context, id, 42L)
        CollectionRepository.addTrack(context, id, 43L)
        val ids1 = (CollectionRepository.snapshot(context).firstOrNull { it.id == id }?.rule as? CollectionRule.Manual)?.trackIds
        CollectionRepository.removeTrack(context, id, 42L)
        val ids2 = (CollectionRepository.snapshot(context).firstOrNull { it.id == id }?.rule as? CollectionRule.Manual)?.trackIds
        CollectionRepository.delete(context, id)
        if (ids1 != listOf(42L, 43L)) throw IllegalStateException("ids1=$ids1")
        if (ids2 != listOf(43L)) throw IllegalStateException("ids2=$ids2")
        "ok"
    }
    test("Collection: Duration matches") {
        val rule = CollectionRule.Duration(minMs = 10 * 60_000L, maxMs = 30 * 60_000L)
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=5*60_000L, contentUri="u")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=15*60_000L, contentUri="u")
        val t3 = Track(id=3L, title="t", artist=null, album=null, durationMs=45*60_000L, contentUri="u")
        if (rule.matches(t1)) throw IllegalStateException("t1 hit")
        if (!rule.matches(t2)) throw IllegalStateException("t2 miss")
        if (rule.matches(t3)) throw IllegalStateException("t3 hit")
        "ok"
    }
    test("Collection: Pattern matches") {
        val rule = CollectionRule.Pattern("podcast", setOf(CollectionRule.Pattern.Field.Folder))
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u", folder="MyPodcasts", filename="x.mp3")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u", folder="Music", filename="y.mp3")
        if (!rule.matches(t1)) throw IllegalStateException("miss")
        if (rule.matches(t2)) throw IllegalStateException("hit")
        "ok"
    }
    test("Collection: rename") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "Orig", CollectionRule.Manual(emptyList()))
        CollectionRepository.rename(context, id, "Renamed")
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        if (found?.label != "Renamed") throw IllegalStateException("label=${found?.label}")
        "ok"
    }
    test("Collection: resolveTracks All") {
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=120_000L, contentUri="v")
        val all = Collection("all", "All", CollectionRule.All, isSystem = true)
        if (CollectionRepository.resolveTracks(listOf(t1, t2), all).size != 2)
            throw IllegalStateException("size")
        "ok"
    }
    test("Collection: resolveTracks Manual") {
        val t1 = Track(id=1L, title="t", artist=null, album=null, durationMs=60_000L, contentUri="u")
        val t2 = Track(id=2L, title="t", artist=null, album=null, durationMs=120_000L, contentUri="v")
        val c = Collection("x", "X", CollectionRule.Manual(listOf(2L)))
        val resolved = CollectionRepository.resolveTracks(listOf(t1, t2), c)
        if (resolved.size != 1 || resolved[0].id != 2L) throw IllegalStateException("resolved=$resolved")
        "ok"
    }
    test("Collection: persistence round-trip") {
        CollectionRepository.initializeIfNeeded(context)
        val id = CollectionRepository.create(context, "Persist", CollectionRule.Duration(minMs=5*60_000L))
        val found = CollectionRepository.snapshot(context).firstOrNull { it.id == id }
        CollectionRepository.delete(context, id)
        val rule = found?.rule as? CollectionRule.Duration
            ?: throw IllegalStateException("rule=${found?.rule}")
        if (rule.minMs != 5*60_000L) throw IllegalStateException("min=${rule.minMs}")
        "ok"
    }
'''
anchor = "    val pass = results.count { it.passed }"
if anchor not in t: raise Exception("pass anchor missing")
t = t.replace(anchor, new_tests + "\n" + anchor, 1)
p.write_text(t, encoding="utf-8")
print("SelfTest OK")
PYEOF

# ── 6.5) Patch PlaybackService.kt ─────────────────────
log "6.5 patch PlaybackService.kt"
python3 <<'PLAYBACK_PATCH'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt")
t = p.read_text(encoding="utf-8")

def sub(search, replacement, label):
    global t
    if search not in t:
        print(f"FAIL [{label}]: search not found")
        sys.exit(2)
    t = t.replace(search, replacement, 1)

sub("import ir.mariaplayer.data.FavoritesPrefs\n",
    "import ir.mariaplayer.data.CollectionRepository\n"
    "import ir.mariaplayer.data.CollectionRule\n",
    "import")

sub("""        scope.launch {
            FavoritesPrefs.observe(this@PlaybackService).collectLatest { favs ->
                favorites = favs
                updateCustomLayout()
            }
        }""",
"""        scope.launch {
            CollectionRepository.observe(this@PlaybackService).collectLatest { list ->
                favorites = (list.firstOrNull { it.id == CollectionRepository.FAVORITES_ID }
                    ?.rule as? CollectionRule.Manual)?.trackIds?.toSet() ?: emptySet()
                updateCustomLayout()
            }
        }""", "observe")

sub("scope.launch { FavoritesPrefs.toggle(this@PlaybackService, id) }",
    "scope.launch { CollectionRepository.toggleFavorite(this@PlaybackService, id) }",
    "toggle")

p.write_text(t, encoding="utf-8")
print("PlaybackService OK")
PLAYBACK_PATCH

# ── Delete old files ──────────────────────────────────
log "delete old files"
rm -f app/src/main/java/ir/mariaplayer/data/FavoritesPrefs.kt
rm -f app/src/main/java/ir/mariaplayer/data/PlaylistPrefs.kt
rm -f app/src/main/java/ir/mariaplayer/data/LibraryTabPrefs.kt
rm -f app/src/main/java/ir/mariaplayer/data/Playlist.kt
rm -f app/src/main/java/ir/mariaplayer/data/LibraryTab.kt
rm -f app/src/main/java/ir/mariaplayer/ui/PlaylistsScreen.kt
rm -f app/src/main/java/ir/mariaplayer/ui/PlaylistDetailScreen.kt
rm -f app/src/main/java/ir/mariaplayer/ui/LibraryTabBar.kt
log "  ✅ 8 files removed"

# ── Repo-wide sanity ──────────────────────────────────
log "repo sanity"
BAD=$(grep -rl --include='*.kt' -E '\b(FavoritesPrefs|PlaylistPrefs|LibraryTabPrefs|LibraryTabBar|LibraryTab|PlaylistsScreen|PlaylistDetailScreen)\b' app/src/main/java/ 2>/dev/null | grep -vE 'CollectionRepository\.kt|Collection\.kt' || true)
if [[ -n "$BAD" ]]; then
    log "❌ stale refs in:"; echo "$BAD"
    fail "stale refs found"
fi
log "  ✅ clean"

# ── Commit + Push ─────────────────────────────────────
log "commit"
git add -A
if git diff --cached --quiet; then log "no changes"; exit 0; fi
git commit -m "refactor(collections): merge Favorites + Playlists + Tabs into one SSOT

The three abstractions (favorites / playlists / tabs) were doing the same
thing and are now ONE model: CollectionRepository.

New SSOT:
- data/Collection.kt: Collection + CollectionRule (All / Manual / Duration /
  Types / Pattern-regex)
- data/CollectionRepository.kt: single DataStore (collections_v2); one-time
  migration from legacy 'favorites' and 'playlists' stores; favorites
  auto-created on first heart; All protected.

Defaults after first run: All, Music (<10m), Podcasts (10m-2h),
Audiobooks (>=2h). Favorites appears on first heart.

UI merge:
- ui/CollectionBar.kt replaces LibraryTabBar — the tab bar IS the playlist list
- ui/PlaylistsScreen.kt deleted (functionality folded into the bar via
  long-press + the '+ New collection' button)
- ui/PlaylistDetailScreen.kt deleted (reorder feature superseded by unified
  filtering; when a collection tab is selected its tracks render in the
  library list)
- New-collection dialog: name + Rule chips (Manual / <10m / 10m-2h / 2h+ / Regex)
- Edit-collection dialog: rename + delete (All protected; everything else
  deletable including Favorites, which auto-recreates on next heart)

Deleted (8 files):
- data/FavoritesPrefs.kt, data/PlaylistPrefs.kt, data/LibraryTabPrefs.kt
- data/Playlist.kt, data/LibraryTab.kt
- ui/PlaylistsScreen.kt, ui/PlaylistDetailScreen.kt, ui/LibraryTabBar.kt

Untouched: ContentType*, MediaClassifier, LibraryPrefs, PlaybackPrefs, Track,
Lyrics*, NowPlayingBar, TrackTypeSheet, ContentTypeFilterBar.

SelfTest:
- removed 24 obsolete favorites/playlist/tab tests
- added 11 CollectionRepository + CollectionRule tests"
log "  ✅ $(git rev-parse --short HEAD)"

if ! git remote | grep -q .; then
    log "⚠️  no remote — local only"; exit 0
fi
log "push"
if ! git push -u origin HEAD; then
    log "⚠️  push failed — retry: git push -u origin HEAD"; exit 2
fi
log "🎉 pushed — Actions will build"
log "بعد از بیلد، دکمهٔ Self-test رو بزن و خروجی کامل رو بفرست"
