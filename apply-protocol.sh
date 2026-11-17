#!/data/data/com.termux/files/usr/bin/bash
# MariaPlayer – Favorites + Smart tabs (SSOT-preserving) — v3
set -Eeuo pipefail
BASE="$(pwd)"; cd "$BASE"
COMMITTED=0
log()  { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { log "❌ $*"; exit 1; }
rollback() {
  log "⚠️  rollback"
  if [[ "$COMMITTED" -eq 1 ]]; then git reset --hard HEAD~1 2>/dev/null && log "commit undone"
  else
    git checkout -- \
      app/src/main/java/ir/mariaplayer/data/LibraryTab.kt \
      app/src/main/java/ir/mariaplayer/data/LibraryTabPrefs.kt \
      app/src/main/java/ir/mariaplayer/data/MediaClassifier.kt \
      app/src/main/java/ir/mariaplayer/MainActivity.kt \
      app/src/main/java/ir/mariaplayer/ui/SelfTestScreen.kt 2>/dev/null || true
    log "files restored"
  fi
}
trap 'rollback' ERR

[[ -d .git ]] || fail "no .git"
git diff --quiet && git diff --cached --quiet || fail "dirty tree"
command -v python3 >/dev/null || fail "python3 needed"

# ── 1) LibraryTab.kt ────────────────────────────────────
log "1/5 LibraryTab.kt"
cat > app/src/main/java/ir/mariaplayer/data/LibraryTab.kt <<'EOF'
package ir.mariaplayer.data

data class LibraryTab(
    val id: String,
    val kind: Kind,
    val label: String,
    val playlistId: Long? = null,
    val isDefault: Boolean = false,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
) {
    enum class Kind { ALL, MUSIC, PODCAST, AUDIOBOOK, PLAYLIST, FAVORITES, SMART }

    fun matches(t: Track, favorites: Set<Long>): Boolean = when (kind) {
        Kind.ALL        -> true
        Kind.MUSIC      -> t.contentType == ContentType.MUSIC
        Kind.PODCAST    -> t.contentType == ContentType.PODCAST
        Kind.AUDIOBOOK  -> t.contentType == ContentType.AUDIOBOOK
        Kind.PLAYLIST   -> true
        Kind.FAVORITES  -> t.id in favorites
        Kind.SMART      -> (minDurationMs?.let { t.durationMs >= it } ?: true) &&
                           (maxDurationMs?.let { t.durationMs <= it } ?: true)
    }

    companion object {
        const val FAVORITES_ID = "favorites"
        const val FAVORITES_LABEL = "Favorites"

        fun defaults(): List<LibraryTab> = listOf(
            LibraryTab("all", Kind.ALL, "All", null, true),
            LibraryTab(FAVORITES_ID, Kind.FAVORITES, FAVORITES_LABEL, null, true),
            LibraryTab("music", Kind.MUSIC, "Music", null, true),
            LibraryTab("podcasts", Kind.PODCAST, "Podcasts", null, true),
            LibraryTab("audiobooks", Kind.AUDIOBOOK, "Audiobooks", null, true),
        )

        fun favorites(): LibraryTab =
            LibraryTab(FAVORITES_ID, Kind.FAVORITES, FAVORITES_LABEL, null, true)

        fun smart(id: String, label: String,
                  minDurationMs: Long? = null, maxDurationMs: Long? = null): LibraryTab =
            LibraryTab(id, Kind.SMART, label, null, false, minDurationMs, maxDurationMs)
    }
}
EOF

# ── 2) LibraryTabPrefs.kt ───────────────────────────────
log "2/5 LibraryTabPrefs.kt"
python3 <<'PYEOF'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/data/LibraryTabPrefs.kt")
t = p.read_text(encoding="utf-8")
edits = [
    ("if (target.isDefault) return",
     "if (target.isDefault && target.kind != LibraryTab.Kind.FAVORITES) return"),
    ('            o.put("isDefault", t.isDefault)\n            arr.put(o)',
     '            o.put("isDefault", t.isDefault)\n'
     '            if (t.minDurationMs != null) o.put("minDurationMs", t.minDurationMs) else o.put("minDurationMs", JSONObject.NULL)\n'
     '            if (t.maxDurationMs != null) o.put("maxDurationMs", t.maxDurationMs) else o.put("maxDurationMs", JSONObject.NULL)\n'
     '            arr.put(o)'),
    ('                        isDefault = o.optBoolean("isDefault", false),\n                    )',
     '                        isDefault = o.optBoolean("isDefault", false),\n'
     '                        minDurationMs = if (o.isNull("minDurationMs")) null else o.getLong("minDurationMs"),\n'
     '                        maxDurationMs = if (o.isNull("maxDurationMs")) null else o.getLong("maxDurationMs"),\n'
     '                    )'),
]
for s, r in edits:
    if s not in t:
        print(f"FAIL: {s[:200]!r}"); sys.exit(2)
    t = t.replace(s, r, 1)
p.write_text(t, encoding="utf-8"); print("OK")
PYEOF

# ── 3) MediaClassifier.kt ───────────────────────────────
log "3/5 MediaClassifier.kt"
python3 <<'PYEOF'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/data/MediaClassifier.kt")
t = p.read_text(encoding="utf-8")
s = "const val LONG_THRESHOLD_MS = 8 * 60_000L"
if s not in t: print("FAIL"); sys.exit(2)
p.write_text(t.replace(s, "const val LONG_THRESHOLD_MS = 10 * 60_000L", 1), encoding="utf-8")
print("OK")
PYEOF

# ── 4) MainActivity.kt ──────────────────────────────────
log "4/5 MainActivity.kt"
python3 <<'PYEOF'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
t = p.read_text(encoding="utf-8")
edits = [
    ("                tabMatches(selectedTab, t) &&",
     "                tabMatches(selectedTab, t, favorites) &&"),
    ("""private fun tabMatches(tab: ir.mariaplayer.data.LibraryTab, t: Track): Boolean =
    when (tab.kind) {
        ir.mariaplayer.data.LibraryTab.Kind.ALL -> true
        ir.mariaplayer.data.LibraryTab.Kind.MUSIC -> t.contentType == ir.mariaplayer.data.ContentType.MUSIC
        ir.mariaplayer.data.LibraryTab.Kind.PODCAST -> t.contentType == ir.mariaplayer.data.ContentType.PODCAST
        ir.mariaplayer.data.LibraryTab.Kind.AUDIOBOOK -> t.contentType == ir.mariaplayer.data.ContentType.AUDIOBOOK
        ir.mariaplayer.data.LibraryTab.Kind.PLAYLIST -> true
    }""",
     """private fun tabMatches(
    tab: ir.mariaplayer.data.LibraryTab,
    t: Track,
    favorites: Set<Long>,
): Boolean = tab.matches(t, favorites)"""),
    ('        var newKind by remember { mutableStateOf(ir.mariaplayer.data.LibraryTab.Kind.MUSIC) }',
     '        var newKind by remember { mutableStateOf(ir.mariaplayer.data.LibraryTab.Kind.MUSIC) }\n'
     '        var smartPreset by remember { mutableStateOf("under10") }'),
    ("""                        listOf(
                            ir.mariaplayer.data.LibraryTab.Kind.MUSIC to "Music",
                            ir.mariaplayer.data.LibraryTab.Kind.PODCAST to "Podcasts",
                            ir.mariaplayer.data.LibraryTab.Kind.AUDIOBOOK to "Audiobooks",
                        ).forEach { (kind, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = newKind == kind,
                                onClick = { newKind = kind },
                                label = { androidx.compose.material3.Text(label) },
                                modifier = androidx.compose.ui.Modifier.padding(end = 6.dp),
                            )
                        }
                    }""",
     """                        listOf(
                            ir.mariaplayer.data.LibraryTab.Kind.MUSIC to "Music",
                            ir.mariaplayer.data.LibraryTab.Kind.PODCAST to "Podcasts",
                            ir.mariaplayer.data.LibraryTab.Kind.AUDIOBOOK to "Audiobooks",
                            ir.mariaplayer.data.LibraryTab.Kind.SMART to "Smart",
                        ).forEach { (kind, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = newKind == kind,
                                onClick = { newKind = kind },
                                label = { androidx.compose.material3.Text(label) },
                                modifier = androidx.compose.ui.Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    if (newKind == ir.mariaplayer.data.LibraryTab.Kind.SMART) {
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                        androidx.compose.material3.Text(
                            "Rule:",
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        )
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                        androidx.compose.foundation.layout.Row {
                            listOf(
                                "under10" to "<10m",
                                "long10" to "10m+",
                                "books2h" to "2h+",
                            ).forEach { (preset, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = smartPreset == preset,
                                    onClick = { smartPreset = preset },
                                    label = { androidx.compose.material3.Text(label) },
                                    modifier = androidx.compose.ui.Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                    }"""),
    ("""                    onClick = {
                        val tab = ir.mariaplayer.data.LibraryTab(
                            id = "custom_" + System.currentTimeMillis(),
                            kind = newKind,
                            label = newName.trim(),
                            isDefault = false,
                        )
                        scope.launch {
                            ir.mariaplayer.data.LibraryTabPrefs.add(context, tab)
                            tabs = ir.mariaplayer.data.LibraryTabPrefs.read(context)
                            selectedTabId = tab.id
                        }
                        showNewTabDialog = false
                    },""",
     """                    onClick = {
                        val (minMs, maxMs): Pair<Long?, Long?> = when {
                            newKind != ir.mariaplayer.data.LibraryTab.Kind.SMART -> null to null
                            smartPreset == "under10" -> null to 10 * 60_000L
                            smartPreset == "long10"  -> 10 * 60_000L to null
                            smartPreset == "books2h" -> 2L * 60 * 60_000L to null
                            else -> null to null
                        }
                        val tab = ir.mariaplayer.data.LibraryTab(
                            id = "custom_" + System.currentTimeMillis(),
                            kind = newKind,
                            label = newName.trim(),
                            isDefault = false,
                            minDurationMs = minMs,
                            maxDurationMs = maxMs,
                        )
                        scope.launch {
                            ir.mariaplayer.data.LibraryTabPrefs.add(context, tab)
                            tabs = ir.mariaplayer.data.LibraryTabPrefs.read(context)
                            selectedTabId = tab.id
                        }
                        showNewTabDialog = false
                    },"""),
    ("""                    if (!tab.isDefault) {
                        androidx.compose.material3.TextButton(onClick = {
                            scope.launch {
                                ir.mariaplayer.data.LibraryTabPrefs.remove(context, tab.id)
                                tabs = ir.mariaplayer.data.LibraryTabPrefs.read(context)
                                if (selectedTabId == tab.id) selectedTabId = "all"
                            }
                            tabToEdit = null
                        }) { androidx.compose.material3.Text("Delete") }
                    }""",
     """                    if (!tab.isDefault || tab.kind == ir.mariaplayer.data.LibraryTab.Kind.FAVORITES) {
                        androidx.compose.material3.TextButton(onClick = {
                            scope.launch {
                                if (tab.kind == ir.mariaplayer.data.LibraryTab.Kind.FAVORITES) {
                                    val favs = ir.mariaplayer.data.FavoritesPrefs.snapshot(context)
                                    for (id in favs) ir.mariaplayer.data.FavoritesPrefs.set(context, id, false)
                                }
                                ir.mariaplayer.data.LibraryTabPrefs.remove(context, tab.id)
                                tabs = ir.mariaplayer.data.LibraryTabPrefs.read(context)
                                if (selectedTabId == tab.id) selectedTabId = "all"
                            }
                            tabToEdit = null
                        }) { androidx.compose.material3.Text("Delete") }
                    }"""),
    ("        onToggleFavorite = { id -> scope.launch { FavoritesPrefs.toggle(context, id) } },",
     """        onToggleFavorite = { id -> scope.launch {
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
        }},"""),
    ("""                    IconButton(onClick = { onFavOnlyChange(!favOnly) }, modifier = sz) {
                        Icon(painter = painterResource(
                                if (favOnly) R.drawable.ic_favorite_filled
                                else R.drawable.ic_favorite_border),
                            contentDescription = if (favOnly) "Favorites filter on"
                                else "Favorites filter off",
                            tint = if (favOnly) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = isz)
                    }
""", ""),
    ("""                        val msg = when {
                            allTracks.isNullOrEmpty() ->
                                "No audio files found.\\nAdd music to /sdcard/Music, then reopen the app."
                            query.isNotBlank() -> "No results for \\"$query\\""
                            favOnly -> "No favorites yet.\\nTap the heart next to a track to add."
                            folderFilter.isNotEmpty() -> "No tracks in \\"$folderFilter\\""
                            else -> "No tracks match the current filters."
                        }""",
     """                        val isFavTab = tabs.firstOrNull { it.id == selectedTabId }?.kind ==
                            ir.mariaplayer.data.LibraryTab.Kind.FAVORITES
                        val msg = when {
                            allTracks.isNullOrEmpty() ->
                                "No audio files found.\\nAdd music to /sdcard/Music, then reopen the app."
                            query.isNotBlank() -> "No results for \\"$query\\""
                            isFavTab -> "No favorites yet.\\nTap the heart next to a track to add."
                            favOnly -> "No favorites yet.\\nTap the heart next to a track to add."
                            folderFilter.isNotEmpty() -> "No tracks in \\"$folderFilter\\""
                            else -> "No tracks match the current filters."
                        }"""),
]
for s, r in edits:
    if s not in t:
        print(f"FAIL: search not found:\n{s[:400]}"); sys.exit(2)
    t = t.replace(s, r, 1)
p.write_text(t, encoding="utf-8"); print("OK")
PYEOF

# ── 5) SelfTestScreen.kt ────────────────────────────────
log "5/5 SelfTestScreen.kt"
python3 <<'PYEOF'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/ui/SelfTestScreen.kt")
t = p.read_text(encoding="utf-8")
edits = [
    ('''    test("Tabs: defaults have 4") {
        val tabs = LibraryTab.defaults()
        if (tabs.size != 4) throw IllegalStateException("size=${tabs.size}")
        if (tabs.map { it.kind }.toSet().size != 4)
            throw IllegalStateException("kinds not unique")
        "4"
    }''',
     '''    test("Tabs: defaults have 5") {
        val tabs = LibraryTab.defaults()
        if (tabs.size != 5) throw IllegalStateException("size=${tabs.size}")
        if (tabs.map { it.kind }.toSet().size != 5)
            throw IllegalStateException("kinds not unique")
        "5"
    }'''),
    ('        val want = setOf("all", "music", "podcasts", "audiobooks")',
     '        val want = setOf("all", "favorites", "music", "podcasts", "audiobooks")'),
    ('        if (got.size != 4) throw IllegalStateException("size=${got.size}")\n        "restored"',
     '        if (got.size != 5) throw IllegalStateException("size=${got.size}")\n        "restored"'),
    ('    val pass = results.count { it.passed }',
     '''    test("Tabs: FAVORITES is default") {
        val fav = LibraryTab.defaults().firstOrNull { it.kind == LibraryTab.Kind.FAVORITES }
            ?: throw IllegalStateException("favorites missing")
        if (fav.id != LibraryTab.FAVORITES_ID) throw IllegalStateException("id=${fav.id}")
        if (!fav.isDefault) throw IllegalStateException("not default")
        "ok"
    }
    test("Tabs: FAVORITES can be removed") {
        val orig = LibraryTabPrefs.read(context)
        LibraryTabPrefs.remove(context, LibraryTab.FAVORITES_ID)
        val got = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, orig)
        if (got.any { it.id == LibraryTab.FAVORITES_ID })
            throw IllegalStateException("still present")
        "removed"
    }
    test("Tabs: SMART round-trip") {
        val orig = LibraryTabPrefs.read(context)
        val tab = LibraryTab(
            id = "custom_smart_" + System.currentTimeMillis(),
            kind = LibraryTab.Kind.SMART,
            label = "Long",
            isDefault = false,
            minDurationMs = 10 * 60_000L,
            maxDurationMs = null,
        )
        LibraryTabPrefs.add(context, tab)
        val got = LibraryTabPrefs.read(context)
        LibraryTabPrefs.save(context, orig)
        val found = got.firstOrNull { it.id == tab.id }
        if (found?.minDurationMs != 10 * 60_000L)
            throw IllegalStateException("min=${found?.minDurationMs}")
        if (found.maxDurationMs != null)
            throw IllegalStateException("max=${found.maxDurationMs}")
        "ok"
    }
    test("Tab.matches: FAVORITES") {
        val t = Track(id = 42L, title = "t", artist = null, album = null,
            durationMs = 60_000L, contentUri = "u")
        val fav = LibraryTab.favorites()
        if (!fav.matches(t, setOf(42L))) throw IllegalStateException("miss")
        if (fav.matches(t, emptySet())) throw IllegalStateException("hit")
        "ok"
    }
    test("Tab.matches: SMART <10m") {
        val sm = LibraryTab.smart("s1", "U", maxDurationMs = 10 * 60_000L - 1)
        val t1 = Track(id = 1L, title = "t", artist = null, album = null,
            durationMs = 5 * 60_000L, contentUri = "u")
        val t2 = Track(id = 2L, title = "t", artist = null, album = null,
            durationMs = 15 * 60_000L, contentUri = "v")
        if (!sm.matches(t1, emptySet())) throw IllegalStateException("t1 rejected")
        if (sm.matches(t2, emptySet())) throw IllegalStateException("t2 accepted")
        "ok"
    }
    test("Tab.matches: SMART >=10m") {
        val sm = LibraryTab.smart("s2", "L", minDurationMs = 10 * 60_000L)
        val t1 = Track(id = 1L, title = "t", artist = null, album = null,
            durationMs = 5 * 60_000L, contentUri = "u")
        val t2 = Track(id = 2L, title = "t", artist = null, album = null,
            durationMs = 15 * 60_000L, contentUri = "v")
        if (sm.matches(t1, emptySet())) throw IllegalStateException("t1 accepted")
        if (!sm.matches(t2, emptySet())) throw IllegalStateException("t2 rejected")
        "ok"
    }
    test("Tab.matches: SMART >=2h") {
        val sm = LibraryTab.smart("s3", "XL", minDurationMs = 2 * 60 * 60_000L)
        val t1 = Track(id = 1L, title = "t", artist = null, album = null,
            durationMs = 30 * 60_000L, contentUri = "u")
        val t2 = Track(id = 2L, title = "t", artist = null, album = null,
            durationMs = 3 * 60 * 60_000L, contentUri = "v")
        if (sm.matches(t1, emptySet())) throw IllegalStateException("t1 accepted")
        if (!sm.matches(t2, emptySet())) throw IllegalStateException("t2 rejected")
        "ok"
    }

    val pass = results.count { it.passed }'''),
]
for s, r in edits:
    if s not in t:
        print(f"FAIL: search not found:\n{s[:300]}"); sys.exit(2)
    t = t.replace(s, r, 1)
p.write_text(t, encoding="utf-8"); print("OK")
PYEOF

# ── Commit + Push ───────────────────────────────────────
log "commit"
git add -A
if git diff --cached --quiet; then log "no changes"; exit 0; fi
git commit -m "feat(tabs): unified Favorites + Smart-duration tabs (SSOT-preserving)

- LibraryTab: Kind.FAVORITES + Kind.SMART + min/maxDurationMs + matches()
- LibraryTabPrefs: encode/decode duration fields; FAVORITES exempt from
  isDefault guard so it can be deleted
- defaults() → 5 tabs (adds Favorites); heart press re-creates it if deleted
- deleting Favorites tab also clears FavoritesPrefs
- new-tab dialog gains Smart chip + Rule presets (<10m / 10m+ / 2h+)
- remove favOnly toolbar button (Favorites tab covers it)
- empty Favorites shows dedicated message
- MediaClassifier threshold 8m → 10m
- SelfTest: defaults 4→5, FAVORITES/SMART/matches tests

Untouched SSOT: FavoritesPrefs, PlaylistPrefs, ContentTypePrefs, LibraryPrefs,
PlaylistsScreen, PlaylistDetailScreen, AddToPlaylistSheet, NowPlayingBar."
COMMITTED=1
log "  ✅ $(git rev-parse --short HEAD)"

if ! git remote | grep -q .; then
    log "⚠️  no remote — commit local only"; exit 0
fi
log "push"
if ! git push -u origin HEAD; then
    log "⚠️  push failed — retry: git push -u origin HEAD"; exit 2
fi
log "🎉 pushed — GitHub Actions building"
log "بعد از بیلد، دکمهٔ Self-test رو بزن و خروجی رو بفرست"
