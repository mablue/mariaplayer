#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

F=app/src/main/java/ir/mariaplayer/playback/MariaNotificationProvider.kt
mkdir -p .bak/fix-icon-delete-constant
cp "$F" .bak/fix-icon-delete-constant/

python3 - << 'PY'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/playback/MariaNotificationProvider.kt")
s = p.read_text()

old = """        val del = CommandButton.Builder(CommandButton.ICON_DELETE)
            .setSessionCommand(SessionCommand(PlaybackService.CMD_DELETE, Bundle.EMPTY))
            .setDisplayName("Delete")
            .build()"""

new = """        // Media3 has no ICON_DELETE constant — use the project vector.
        val del = CommandButton.Builder()
            .setSessionCommand(SessionCommand(PlaybackService.CMD_DELETE, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_delete)
            .setDisplayName("Delete")
            .build()"""

if old not in s:
    print("FAIL: delete CommandButton block not found")
    sys.exit(1)

s = s.replace(old, new, 1)
p.write_text(s)
print("patched: delete button -> setCustomIconResId(R.drawable.ic_delete)")
PY

git add -A
git commit -qm "fix(notif): ICON_DELETE does not exist in Media3 — use ic_delete vector for delete button"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
