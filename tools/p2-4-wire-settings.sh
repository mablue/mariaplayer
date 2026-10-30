#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
s = p.read_text()

# add imports
for imp in [
    "import ir.mariaplayer.ui.SettingsScreen",
]:
    if imp not in s:
        s = s.replace(
            "import ir.mariaplayer.ui.PermissionScreen\n",
            "import ir.mariaplayer.ui.PermissionScreen\n" + imp + "\n", 1)

# add settings state in AppRoot
if "var showSettings" not in s:
    s = s.replace(
        "    var sortMode by remember { mutableStateOf(SortMode.TITLE) }",
        "    var showSettings by remember { mutableStateOf(false) }\n"
        "    var sortMode by remember { mutableStateOf(SortMode.TITLE) }", 1)

# add settings branch before LibraryScreen call
if "showSettings" in s and "SettingsScreen(" not in s:
    s = s.replace(
        "    LibraryScreen(\n        allTracks = tracks,",
        "    if (showSettings) {\n"
        "        SettingsScreen(\n"
        "            tracks = tracks.orEmpty(),\n"
        "            onBack = { showSettings = false },\n"
        "        )\n"
        "        return\n"
        "    }\n\n"
        "    LibraryScreen(\n        allTracks = tracks,", 1)

# add Settings action in TopAppBar
if "onSettingsClick" not in s:
    s = s.replace(
        "        onShuffleClick = {",
        "        onSettingsClick = { showSettings = true },\n"
        "        onShuffleClick = {", 1)

# add param to LibraryScreen signature
if "onSettingsClick: () -> Unit" not in s:
    s = s.replace(
        "    onShuffleClick: () -> Unit,",
        "    onSettingsClick: () -> Unit,\n"
        "    onShuffleClick: () -> Unit,", 1)

# add Settings button in TopAppBar actions (before Sort)
if "Settings\"" not in s:
    s = s.replace(
        '                    Box {\n'
        '                        TextButton(onClick = { sortMenuOpen = true }) {',
        '                    TextButton(onClick = onSettingsClick) { Text("Settings") }\n'
        '                    Box {\n'
        '                        TextButton(onClick = { sortMenuOpen = true }) {', 1)

p.write_text(s)
print("MainActivity wired to SettingsScreen")
PY

git add -A
git commit -qm "feat(P2-4): tag-based favorites — export/import [MARIA_FAV] COMMENT marker via jaudiotagger (one createWriteRequest for batch)"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
