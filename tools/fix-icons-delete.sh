#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

mkdir -p .bak/fix-icons-delete
for f in app/src/main/java/ir/mariaplayer/MainActivity.kt \
         app/src/main/java/ir/mariaplayer/DeleteActivity.kt \
         app/src/main/java/ir/mariaplayer/playback/NowPlayingBar.kt \
         app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt \
         app/src/main/AndroidManifest.xml ; do
  [ -f "$f" ] && cp "$f" ".bak/fix-icons-delete/$(basename "$f")"
done

# ── 1. Material 24dp vector icons (no material-icons-extended) ──────
D=app/src/main/res/drawable
mkdir -p "$D"

cat > "$D/ic_sort.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z"/>
</vector>
EOF

cat > "$D/ic_shuffle.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z"/>
</vector>
EOF

cat > "$D/ic_folder.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"/>
</vector>
EOF

cat > "$D/ic_close.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"/>
</vector>
EOF

cat > "$D/ic_play_arrow.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M8,5v14l11,-7z"/>
</vector>
EOF

cat > "$D/ic_pause.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M6,19h4V5H6v14zM14,5v14h4V5h-4z"/>
</vector>
EOF

cat > "$D/ic_skip_previous.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M6,6h2v12H6zM9.5,12l8.5,6V6z"/>
</vector>
EOF

cat > "$D/ic_skip_next.xml" << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"/>
</vector>
EOF

echo "1/6 icons written"

# ── 2. DeleteActivity — launcher registered before STARTED + dialog theme ──
cat > app/src/main/java/ir/mariaplayer/DeleteActivity.kt << 'KOTEOF'
package ir.mariaplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import ir.mariaplayer.playback.PlaybackService

class DeleteActivity : ComponentActivity() {

    private var pendingUri: Uri? = null
    private var pendingTitle: String = ""

    // Registered as a field initializer → runs in the constructor, before
    // the Activity reaches STARTED. Calling registerForActivityResult()
    // later (e.g. from a dialog button) throws IllegalStateException.
    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingUri
        val title = pendingTitle
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
            notifyDeleted(uri)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (uriStr == null) { finish(); return }

        val uri = Uri.parse(uriStr)
        pendingUri = uri
        pendingTitle = title

        val folder = queryFolder(uri)
        val msg = buildString {
            append("\"$title\"")
            if (folder.isNotBlank()) append("\n\nFolder: ").append(folder)
            append("\n\nThis permanently deletes the file. Cannot be undone.")
        }

        AlertDialog.Builder(this)
            .setTitle("Delete music file?")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> proceedDelete() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun queryFolder(uri: Uri): String = try {
        val col = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.RELATIVE_PATH
        else
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.getColumnCount() > 0) c.getString(0) ?: "" else ""
        } ?: ""
    } catch (_: Exception) { "" }

    private fun proceedDelete() {
        val uri = pendingUri ?: run { finish(); return }
        val title = pendingTitle

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot delete: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            try {
                contentResolver.delete(uri, null, null)
                Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                notifyDeleted(uri)
            } catch (e: Exception) {
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun notifyDeleted(uri: Uri) {
        sendBroadcast(
            Intent(PlaybackService.ACTION_TRACK_DELETED)
                .setPackage(packageName)
                .putExtra(PlaybackService.EXTRA_DELETED_URI, uri.toString())
        )
    }

    companion object {
        const val EXTRA_URI = "delete_uri"
        const val EXTRA_TITLE = "delete_title"
    }
}
KOTEOF

echo "2/6 DeleteActivity fixed"

# ── 3. Manifest — dialog theme instead of Theme.Translucent ─────────
python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
s = s.replace(
    'android:theme="@android:style/Theme.Translucent.NoTitleBar"',
    'android:theme="@android:style/Theme.Material.Dialog.Alert"')
p.write_text(s)
print("manifest: DeleteActivity theme -> Theme.Material.Dialog.Alert")
PY

echo "3/6 manifest patched"
