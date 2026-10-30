#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

F=app/src/main/java/ir/mariaplayer/data/TagFavorites.kt
mkdir -p .bak/fix-tagfavorites-return
cp "$F" .bak/fix-tagfavorites-return/

python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/java/ir/mariaplayer/data/TagFavorites.kt")
s = p.read_text()

old = '''    private fun copyToTemp(context: Context, uri: Uri): File? = try {
        val ext = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "mp3"
        val f = File.createTempFile("tag_", ".$ext", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: return null
        f
    } catch (e: Exception) {
        Log.w(TAG, "copyToTemp failed for $uri: ${e.message}")
        null
    }'''

new = '''    private fun copyToTemp(context: Context, uri: Uri): File? {
        return try {
            val ext = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "mp3"
            val f = File.createTempFile("tag_", ".$ext", context.cacheDir)
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                f.delete()
                null
            } else {
                stream.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                f
            }
        } catch (e: Exception) {
            Log.w(TAG, "copyToTemp failed for $uri: ${e.message}")
            null
        }
    }'''

if old not in s:
    print("pattern not found — check the file manually")
    raise SystemExit(1)

s = s.replace(old, new, 1)
p.write_text(s)
print("copyToTemp fixed: block body + null-safe stream")
PY

git add -A
git commit -qm "fix(P2-4): copyToTemp — block body (no return in expression-bodied fn)"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
