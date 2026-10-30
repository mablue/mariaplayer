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
