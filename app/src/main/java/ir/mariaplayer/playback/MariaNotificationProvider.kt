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
