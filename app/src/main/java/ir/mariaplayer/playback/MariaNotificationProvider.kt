package ir.mariaplayer.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import ir.mariaplayer.R

class MariaNotificationProvider(
    private val ctx: Context,
    private val favoritesProvider: () -> Set<Long>,
) : DefaultMediaNotificationProvider(ctx) {

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showInCompactView: Boolean,
    ): ImmutableList<CommandButton> {
        val player = session.player

        val prev = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_to_previous)
            .setDisplayName("Previous")
            .setEnabled(player.hasPreviousMediaItem())
            .build()

        val playPause = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setIconResId(
                if (player.playWhenReady)
                    androidx.media3.session.R.drawable.media3_notification_pause
                else
                    androidx.media3.session.R.drawable.media3_notification_play
            )
            .setDisplayName(if (player.playWhenReady) "Pause" else "Play")
            .build()

        val next = CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setIconResId(androidx.media3.session.R.drawable.media3_notification_seek_to_next)
            .setDisplayName("Next")
            .setEnabled(player.hasNextMediaItem())
            .build()

        if (showInCompactView) {
            return ImmutableList.of(prev, playPause, next)
        }

        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val isFav = currentId != null && currentId in favoritesProvider()

        val fav = CommandButton.Builder()
            .setSessionCommand(SessionCommand(PlaybackService.CMD_FAVORITE, Bundle.EMPTY))
            .setCustomIconResId(
                if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
            )
            .setDisplayName(if (isFav) "Unfavorite" else "Favorite")
            .setEnabled(true)
            .build()

        val del = CommandButton.Builder()
            .setSessionCommand(SessionCommand(PlaybackService.CMD_DELETE, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_delete)
            .setDisplayName("Delete")
            .setEnabled(true)
            .build()

        // HyperOS renders up to 5 actions. Order: fav, prev, play, next, delete
        return ImmutableList.of(fav, prev, playPause, next, del)
    }
}
