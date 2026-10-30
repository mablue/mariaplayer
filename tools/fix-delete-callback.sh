#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

F=app/src/main/java/ir/mariaplayer/MainActivity.kt
mkdir -p .bak/fix-delete-callback
cp "$F" .bak/fix-delete-callback/

python3 - << 'PY'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
s = p.read_text()
changed = []

# 1. Import Intent for the delete launcher
if "import android.content.Intent" not in s:
    s = s.replace(
        "import android.Manifest\n",
        "import android.Manifest\nimport android.content.Intent\n", 1)
    changed.append("+ import android.content.Intent")

# 2. Add parameter to LibraryScreen signature
if "onDeleteRequest: (Track) -> Unit" not in s:
    old = "    onTrackClick: (Track) -> Unit,"
    new = "    onTrackClick: (Track) -> Unit,\n    onDeleteRequest: (Track) -> Unit,"
    if old not in s:
        print("FAIL: LibraryScreen signature anchor missing")
        sys.exit(1)
    s = s.replace(old, new, 1)
    changed.append("+ LibraryScreen onDeleteRequest param")

# 3. Wire the lambda from AppRoot call site
if "onDeleteRequest = { t ->" not in s:
    anchor = "            if (idx >= 0) playQueue(c, list, idx, 0L, autoPlay = true)\n        },"
    replacement = (
        "            if (idx >= 0) playQueue(c, list, idx, 0L, autoPlay = true)\n"
        "        },\n"
        "        onDeleteRequest = { t ->\n"
        "            val intent = Intent(context, DeleteActivity::class.java).apply {\n"
        "                putExtra(DeleteActivity.EXTRA_URI, t.contentUri)\n"
        "                putExtra(DeleteActivity.EXTRA_TITLE, t.title)\n"
        "            }\n"
        "            context.startActivity(intent)\n"
        "        },"
    )
    if anchor not in s:
        print("FAIL: onTrackClick lambda anchor missing")
        sys.exit(1)
    s = s.replace(anchor, replacement, 1)
    changed.append("+ AppRoot onDeleteRequest lambda")

p.write_text(s)
for c in changed: print("  " + c)
print("patched MainActivity.kt")
PY

git add -A
git commit -qm "fix(P3): wire onDeleteRequest callback — launch DeleteActivity from list row"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
