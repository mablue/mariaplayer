package ir.mariaplayer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ir.mariaplayer.DeleteActivity
import ir.mariaplayer.MainActivity
import ir.mariaplayer.R
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
        setMediaNotificationProvider(MariaNotificationProvider(this) { favorites })

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
            .setCustomIconResId(
                if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
            )
            .setDisplayName(if (isFav) "Unfavorite" else "Favorite")
            .build()

        val del = CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_DELETE, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_delete)
            .setDisplayName("Delete track")
            .build()

        session.setCustomLayout(ImmutableList.of(fav, del))
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SessionCommand(CMD_FAVORITE, Bundle.EMPTY))
            .add(SessionCommand(CMD_DELETE, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            CMD_FAVORITE -> {
                val id = session.player.currentMediaItem?.mediaId?.toLongOrNull()
                if (id != null) {
                    scope.launch { FavoritesPrefs.toggle(this@PlaybackService, id) }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CMD_DELETE -> {
                val item = session.player.currentMediaItem
                val uri = item?.localConfiguration?.uri
                val title = item?.mediaMetadata?.title?.toString().orEmpty()
                if (uri != null) requestDelete(uri, title)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    /**
     * A MediaSessionService cannot start an Activity directly on Android 10+.
     * Try the direct launch (works when the app is foregrounded). If the
     * background-activity-launch guard rejects it, post a heads-up
     * notification whose action is a getActivity PendingIntent — a
     * notification-initiated PendingIntent is granted BAL.
     */
    private fun requestDelete(uri: Uri, title: String) {
        // A MediaSessionService cannot reliably start an Activity on Android 10+.
        // Post a heads-up notification whose action PendingIntent is granted BAL.
        postDeleteConfirmNotification(uri, title)
    }

    private fun postDeleteConfirmNotification(uri: Uri, title: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val channelId = CHANNEL_DELETE
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "Delete confirmation", NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val pi = PendingIntent.getActivity(
            this, uri.hashCode(),
            Intent(this, DeleteActivity::class.java).apply {
                putExtra(DeleteActivity.EXTRA_URI, uri.toString())
                putExtra(DeleteActivity.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_delete)
            .setContentTitle("Delete \"$title\"?")
            .setContentText("Tap Delete to confirm. This cannot be undone.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_delete, "Delete", pi)
            .build()
        nm.notify(NOTIF_DELETE_ID, n)
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
        const val CMD_DELETE = "ir.mariaplayer.DELETE"
        const val ACTION_TRACK_DELETED = "ir.mariaplayer.TRACK_DELETED"
        const val EXTRA_DELETED_URI = "deleted_uri"
        private const val CHANNEL_DELETE = "delete_confirm"
        private const val NOTIF_DELETE_ID = 9001
    }
}
