#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

mkdir -p .bak/p2-3
for f in app/src/main/AndroidManifest.xml \
         app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt ; do
  [ -f "$f" ] && cp "$f" ".bak/p2-3/$(basename "$f")"
done

# ── DeleteActivity (transparent, system delete dialog) ──────────────
cat > app/src/main/java/ir/mariaplayer/DeleteActivity.kt << 'KOTEOF'
package ir.mariaplayer

import android.app.Activity
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

# ── Notification provider: adds raw Delete action ──────────────────
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
    ): ImmutableList<Int> {
        val compact = super.addNotificationActions(
            mediaSession, mediaButtons, builder, actionFactory
        )
        val item = mediaSession.player.currentMediaItem ?: return compact
        val uri = item.localConfiguration?.uri ?: return compact
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
        builder.addAction(android.R.drawable.ic_menu_delete, "Delete", pi)
        return compact
    }
}
KOTEOF

# ── PlaybackService: onCustomCommand favorite + delete receiver ────
cat > app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt << 'KOTEOF'
package ir.mariaplayer.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.ConnectionResult
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ir.mariaplayer.MainActivity
import ir.mariaplayer.data.FavoritesPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService(), MediaSession.Callback {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var favorites: Set<Long> = emptySet()

    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deletedUri = intent?.getStringExtra(EXTRA_DELETED_URI) ?: return
            val player = mediaSession?.player ?: return
            val current = player.currentMediaItem ?: return
            if (current.localConfiguration?.uri.toString() == deletedUri) {
                player.seekToNextMediaItem()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(MariaNotificationProvider(this))

        scope.launch {
            FavoritesPrefs.observe(this@PlaybackService).collectLatest { favs ->
                favorites = favs
                updateCustomLayout()
            }
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCustomLayout()
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(this)
            .build()

        updateCustomLayout()

        val filter = IntentFilter(ACTION_TRACK_DELETED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(deleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(deleteReceiver, filter)
        }
    }

    private fun updateCustomLayout() {
        val session = mediaSession ?: return
        val id = session.player.currentMediaItem?.mediaId?.toLongOrNull()
        val isFav = id != null && id in favorites
        val fav = CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .setIconResId(
                if (isFav) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            .setDisplayName(if (isFav) "Unfavorite" else "Favorite")
            .build()
        session.setCustomLayout(ImmutableList.of(fav))
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ConnectionResult {
        val commands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .build()
        return ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction == CMD_FAVORITE) {
            val id = session.player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != null) {
                scope.launch { FavoritesPrefs.toggle(this@PlaybackService, id) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        try { unregisterReceiver(deleteReceiver) } catch (_: Exception) {}
        scope.cancel()
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CMD_FAVORITE = "ir.mariaplayer.FAVORITE"
        const val ACTION_TRACK_DELETED = "ir.mariaplayer.TRACK_DELETED"
        const val EXTRA_DELETED_URI = "deleted_uri"
    }
}
KOTEOF

# ── Manifest: register DeleteActivity ───────────────────────────────
python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
if "DeleteActivity" not in s:
    s = s.replace(
        '        <activity android:name=".MainActivity" android:exported="true">',
        '        <activity\n'
        '            android:name=".DeleteActivity"\n'
        '            android:exported="false"\n'
        '            android:excludeFromRecents="true"\n'
        '            android:noHistory="true"\n'
        '            android:theme="@android:style/Theme.Translucent.NoTitleBar" />\n'
        '        <activity android:name=".MainActivity" android:exported="true">',
        1)
    p.write_text(s)
    print("manifest updated")
else:
    print("manifest already has DeleteActivity")
PY

git add -A
git commit -qm "feat(P2-3): notification actions — ♥ favorite (custom command) + Delete (system dialog)"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
