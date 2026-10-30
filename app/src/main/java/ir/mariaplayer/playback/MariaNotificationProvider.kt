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

        // Transport controls — use CommandButton.ICON_* constants.
        // These are guaranteed to exist and are what HyperOS reads
        // from the PlaybackState actions.
        val prev = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setDisplayName("Previous")
            .setEnabled(player.hasPreviousMediaItem())
            .build()

        val playPause = CommandButton.Builder(
            if (player.playWhenReady) CommandButton.ICON_PAUSE
            else CommandButton.ICON_PLAY
        )
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName(if (player.playWhenReady) "Pause" else "Play")
            .build()

        val next = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setDisplayName("Next")
            .setEnabled(player.hasNextMediaItem())
            .build()

        if (showInCompactView) {
            return ImmutableList.of(prev, playPause, next)
        }

        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val isFav = currentId != null && currentId in favoritesProvider()

        val fav = CommandButton.Builder(
            if (isFav) CommandButton.ICON_HEART_FILLED
            else CommandButton.ICON_HEART_UNFILLED
        )
            .setSessionCommand(SessionCommand(PlaybackService.CMD_FAVORITE, Bundle.EMPTY))
            .setDisplayName(if (isFav) "Unfavorite" else "Favorite")
            .build()

        // Media3 has no ICON_DELETE constant — use the project vector.
        val del = CommandButton.Builder()
            .setSessionCommand(SessionCommand(PlaybackService.CMD_DELETE, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_delete)
            .setDisplayName("Delete")
            .build()

        // HyperOS renders up to 5 actions. Order: fav, prev, play, next, delete
        return ImmutableList.of(fav, prev, playPause, next, del)
    }
}
