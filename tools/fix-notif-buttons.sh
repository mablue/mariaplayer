#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

mkdir -p .bak/fix-notif-buttons
cp app/src/main/java/ir/mariaplayer/playback/MariaNotificationProvider.kt .bak/fix-notif-buttons/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt .bak/fix-notif-buttons/ 2>/dev/null || true

# ── Provider: 5 actions expanded, 3 compact ─────────────────────────
cat > app/src/main/java/ir/mariaplayer/playback/MariaNotificationProvider.kt << 'KOTEOF'
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
KOTEOF

# ── Service: pass favorites; delete click posts BAL-safe notification ──
python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt")
s = p.read_text()

s = s.replace(
    "setMediaNotificationProvider(MariaNotificationProvider(this))",
    "setMediaNotificationProvider(MariaNotificationProvider(this) { favorites })")

old = '''    private fun requestDelete(uri: Uri, title: String) {
        val intent = Intent(this, DeleteActivity::class.java).apply {
            putExtra(DeleteActivity.EXTRA_URI, uri.toString())
            putExtra(DeleteActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            postDeleteConfirmNotification(uri, title)
        }
    }'''

new = '''    private fun requestDelete(uri: Uri, title: String) {
        // A MediaSessionService cannot reliably start an Activity on Android 10+.
        // Post a heads-up notification whose action PendingIntent is granted BAL.
        postDeleteConfirmNotification(uri, title)
    }'''

if old in s:
    s = s.replace(old, new)
    print("requestDelete -> always notification")
else:
    print("WARN: requestDelete block not found; check manually")

p.write_text(s)
PY

git add -A
git commit -qm "fix(notif): override getMediaButtons (5 expanded / 3 compact); delete click posts BAL-safe confirm notification"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
