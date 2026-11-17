package ir.mariaplayer.playback

import android.app.ActivityManager
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
import android.os.Process
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
import ir.mariaplayer.MainActivity
import ir.mariaplayer.R
import ir.mariaplayer.data.CollectionRepository
import ir.mariaplayer.data.CollectionRule
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

    // PR-L33: guard against infinite auto-skip when every track in the queue
    // fails (e.g. user deleted the files from a file manager). Three rapid
    // errors → pause instead of eating the queue with toasts.
    private var consecutivePlaybackErrors = 0
    private var lastPlaybackErrorMs = 0L

    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deletedUri = intent?.getStringExtra(EXTRA_DELETED_URI) ?: return
            val player = mediaSession?.player ?: return
            val toRemove = ArrayList<Int>()
            for (i in 0 until player.mediaItemCount) {
                if (player.getMediaItemAt(i).localConfiguration?.uri?.toString() == deletedUri) {
                    toRemove.add(i)
                }
            }
            for (i in toRemove.sortedDescending()) {
                try { player.removeMediaItem(i) }
                catch (e: Exception) {
                    android.util.Log.w("PlaybackService", "removeMediaItem($i) failed", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(MariaNotificationProvider(this) { favorites })

        scope.launch {
            CollectionRepository.observe(this@PlaybackService).collectLatest { list ->
                favorites = (list.firstOrNull { it.id == CollectionRepository.FAVORITES_ID }
                    ?.rule as? CollectionRule.Manual)?.trackIds?.toSet() ?: emptySet()
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

        // Lambda-28: typed playback errors, user-visible toast, auto-skip.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e(
                    "PlaybackService",
                    "playback error: " + error.errorCodeName,
                    error,
                )
                val msg: String = when (error.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "File not found - skipping"
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                        "Permission denied - skipping"
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        "Codec not supported - skipping"
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Network error - skipping"
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        "File is corrupt - skipping"
                    else -> "Playback error - skipping"
                }
                android.widget.Toast.makeText(
                    this@PlaybackService, msg, android.widget.Toast.LENGTH_LONG
                ).show()

                val now = android.os.SystemClock.elapsedRealtime()
                consecutivePlaybackErrors =
                    if (now - lastPlaybackErrorMs < 3000L) consecutivePlaybackErrors + 1
                    else 1
                lastPlaybackErrorMs = now

                if (consecutivePlaybackErrors >= 3) {
                    consecutivePlaybackErrors = 0
                    player.pause()
                    android.widget.Toast.makeText(
                        this@PlaybackService,
                        "Multiple tracks failed to play — paused",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                } else {
                    player.pause()
                }
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
                    scope.launch { CollectionRepository.toggleFavorite(this@PlaybackService, id) }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CMD_DELETE -> {
                val id = session.player.currentMediaItem?.mediaId?.toLongOrNull()
                if (id != null) {
                    scope.launch {
                        CollectionRepository.trash(this@PlaybackService, id)
                        val player = session.player
                        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                        else player.pause()
                    }
                }
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
        private const val CHANNEL_DELETE = "delete_confirm"
        private const val NOTIF_DELETE_ID = 9001
    }
}
