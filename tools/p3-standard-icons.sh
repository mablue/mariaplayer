#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

mkdir -p .bak/p3-standard-icons
for f in app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt \
         app/src/main/java/ir/mariaplayer/playback/MariaNotificationProvider.kt \
         app/src/main/java/ir/mariaplayer/MainActivity.kt ; do
  [ -f "$f" ] && cp "$f" ".bak/p3-standard-icons/$(basename "$f")"
done

# ── 1. Material 标准矢量图标（心形实心/空心、删除）────────────────
mkdir -p app/src/main/res/drawable

cat > app/src/main/res/drawable/ic_favorite_filled.xml << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path android:fillColor="#FFFFFF"
        android:pathData="M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"/>
</vector>
EOF

cat > app/src/main/res/drawable/ic_favorite_border.xml << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path android:fillColor="#FFFFFF"
        android:pathData="M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z"/>
</vector>
EOF

cat > app/src/main/res/drawable/ic_delete.xml << 'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path android:fillColor="#FFFFFF"
        android:pathData="M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"/>
</vector>
EOF

echo "1/5 drawables created"

# ── 2. PlaybackService：心形图标 + 收藏状态变化时刷新 ─────────────
python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt")
s = p.read_text()

old = '''        val fav = CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .setIconResId(
                if (isFav) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            .setDisplayName(if (isFav) "Unfavorite" else "Favorite")
            .build()'''

new = '''        val fav = CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .setCustomIconResId(
                if (isFav) ir.mariaplayer.R.drawable.ic_favorite_filled
                else ir.mariaplayer.R.drawable.ic_favorite_border
            )
            .setDisplayName(if (isFav) "Unfavorite" else "Favorite")
            .build()'''

if old not in s:
    print("  WARN: favourite button block not found (may already be patched)")
else:
    s = s.replace(old, new, 1)
    print("  PlaybackService favourite icon -> heart vector")

# 收藏状态变化时刷新通知（toggle 之后）
if "favorites = favs\n                updateCustomLayout()" not in s:
    s = s.replace(
        "favorites = favs\n",
        "favorites = favs\n                updateCustomLayout()\n",
        1)
    print("  refresh on favourite change added")

p.write_text(s)
PY

# ── 3. MariaNotificationProvider：删除按钮（getActivity PendingIntent）──
cat > app/src/main/java/ir/mariaplayer/playback/MariaNotificationProvider.kt << 'KOTEOF'
package ir.mariaplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import ir.mariaplayer.DeleteActivity

class MariaNotificationProvider(
    private val ctx: Context,
) : DefaultMediaNotificationProvider(ctx) {

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        val added = super.addNotificationActions(
            mediaSession, mediaButtons, builder, actionFactory
        )

        val item = mediaSession.player.currentMediaItem ?: return added
        val uri = item.localConfiguration?.uri ?: return added
        val title = item.mediaMetadata.title?.toString().orEmpty()

        val intent = Intent(ctx, DeleteActivity::class.java).apply {
            putExtra(DeleteActivity.EXTRA_URI, uri.toString())
            putExtra(DeleteActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            uri.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(
            ir.mariaplayer.R.drawable.ic_delete,
            "Delete",
            pi,
        )
        return added
    }
}
KOTEOF
echo "2/5 MariaNotificationProvider: delete action"

# ── 4. MainActivity：应用内心形/删除按钮 + 去掉 Settings ──────────
python3 - << 'PY'
import pathlib, re
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
s = p.read_text()

# 4a. 移除 SettingsScreen import + 导航
s = s.replace("import ir.mariaplayer.ui.SettingsScreen\n", "")
s = re.sub(
    r"\n    if \(showSettings\) \{[\s\S]*?return\n    \}\n",
    "\n", s)
s = s.replace("    var showSettings by remember { mutableStateOf(false) }\n", "")
s = s.replace("        onSettingsClick = { showSettings = true },\n", "")
s = s.replace("    onSettingsClick: () -> Unit,\n", "")
s = s.replace('                    TextButton(onClick = onSettingsClick) { Text("Settings") }\n', "")

# 4b. trailingContent: 心形 + 删除（标准 vector）
old_trailing = '''                                trailingContent = {
                                    TextButton(onClick = { onToggleFavorite(t.id) }) {
                                        Text(
                                            if (t.id in favorites) "♥" else "♡",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                },'''

new_trailing = '''                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { onToggleFavorite(t.id) }) {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(
                                                    if (t.id in favorites) R.drawable.ic_favorite_filled
                                                    else R.drawable.ic_favorite_border
                                                ),
                                                contentDescription = if (t.id in favorites) "Unfavorite" else "Favorite",
                                                tint = if (t.id in favorites) MaterialTheme.colorScheme.primary
                                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { onDeleteRequest(t) }) {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_delete),
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },'''

if old_trailing not in s:
    print("  WARN: trailing block not found — check MainActivity manually")
else:
    s = s.replace(old_trailing, new_trailing, 1)
    print("  trailing: heart + delete icons")

# 4c. 移除 emoji 收藏按钮
s = s.replace('''                TextButton(onClick = { onFavOnlyChange(!favOnly) }) {
                    Text(if (favOnly) "♥ Favorites" else "♡ Favorites")
                }''', '''                TextButton(onClick = { onFavOnlyChange(!favOnly) }) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            if (favOnly) R.drawable.ic_favorite_filled
                            else R.drawable.ic_favorite_border
                        ),
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (favOnly) "Favorites ON" else "Favorites")
                }''')

# 4d. 需要的 import
for imp in [
    "import androidx.compose.material3.Icon",
    "import androidx.compose.material3.IconButton",
    "import androidx.compose.foundation.layout.Row",
    "import androidx.compose.foundation.layout.Spacer",
    "import androidx.compose.foundation.layout.width",
]:
    if imp not in s:
        s = s.replace("import androidx.compose.material3.ListItem\n",
                      "import androidx.compose.material3.ListItem\n" + imp + "\n", 1)

p.write_text(s)
print("  MainActivity: settings removed, standard icons wired")
PY

echo "3/5 MainActivity updated"

# ── 5. DeleteActivity 保留（系统对话框）+ 确保图标 ─────────────────
if [ -f app/src/main/java/ir/mariaplayer/DeleteActivity.kt ]; then
    echo "4/5 DeleteActivity present"
else
    echo "4/5 WARN: DeleteActivity missing — re-creating"
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (uriStr == null) { finish(); return }
        val uri = Uri.parse(uriStr)
        val folder = queryFolder(uri)
        val msg = buildString {
            append("\"$title\"")
            if (folder.isNotBlank()) append("\n\nFolder: ").append(folder)
            append("\n\nThis permanently deletes the file. Cannot be undone.")
        }
        AlertDialog.Builder(this)
            .setTitle("Delete music file?")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> proceedDelete(uri, title) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun queryFolder(uri: Uri): String = try {
        val col = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH
                  else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.getColumnCount() > 0) c.getString(0) ?: "" else ""
        } ?: ""
    } catch (_: Exception) { "" }

    private fun proceedDelete(uri: Uri, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                    notifyDeleted(uri)
                }
                finish()
            }
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
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
fi

git add -A
git commit -qm "feat(P3): standard Material vector icons (heart filled/border, delete) — notification heart toggle + delete action + in-app delete; remove Settings screen"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "5/5 pushed"
