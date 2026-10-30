#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

F=app/src/main/java/ir/mariaplayer/MainActivity.kt
mkdir -p .bak/fix-launch; cp "$F" .bak/fix-launch/

python3 - << 'PY'
import pathlib, re
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
s = p.read_text()

# 1) add missing import
if "import kotlinx.coroutines.launch" not in s:
    s = s.replace(
        "import kotlinx.coroutines.delay\n",
        "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch\n", 1)

# 2) replace MainScope() leak with rememberCoroutineScope
s = s.replace(
    "import androidx.compose.runtime.saveable.rememberSaveable\n",
    "import androidx.compose.runtime.rememberCoroutineScope\n"
    "import androidx.compose.runtime.saveable.rememberSaveable\n", 1)

s = s.replace(
    "    val controller = rememberPlayerController()\n",
    "    val controller = rememberPlayerController()\n"
    "    val scope = rememberCoroutineScope()\n", 1)

s = s.replace(
    "        onToggleFavorite = { id ->\n"
    "            kotlinx.coroutines.MainScope().launchFavorite(context, id)\n"
    "        },",
    "        onToggleFavorite = { id ->\n"
    "            scope.launch { FavoritesPrefs.toggle(context, id) }\n"
    "        },", 1)

# 3) remove the old leaky extension
s = re.sub(
    r"private fun kotlinx\.coroutines\.CoroutineScope\.launchFavorite\([\s\S]*?\n\}\n\n",
    "", s)

p.write_text(s)
print("MainActivity.kt patched")
PY

git add -A
git commit -qm "fix(P2-2): import kotlinx.coroutines.launch + replace MainScope leak with rememberCoroutineScope"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
