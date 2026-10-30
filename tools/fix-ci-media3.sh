#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

mkdir -p .bak/fix-ci-media3
cp app/src/main/java/ir/mariaplayer/playback/MariaNotificationProvider.kt .bak/fix-ci-media3/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt .bak/fix-ci-media3/ 2>/dev/null || true

# ── Fix 1: MariaNotificationProvider — return type IntArray ──────────
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
        // media3 1.5.1: super returns IntArray (indices of actions it added)
        val added: IntArray = super.addNotificationActions(
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
        builder.addAction(android.R.drawable.ic_menu_delete, "Delete", pi)
        return added
    }
}
KOTEOF

# ── Fix 2: PlaybackService — import MediaSession.ConnectionResult ────
python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt")
s = p.read_text()

# remove wrong top-level import
s = s.replace("import androidx.media3.session.ConnectionResult\n", "")

# add correct nested imports (if absent)
if "import androidx.media3.session.MediaSession.ConnectionResult" not in s:
    s = s.replace(
        "import androidx.media3.session.MediaSession\n",
        "import androidx.media3.session.MediaSession\n"
        "import androidx.media3.session.MediaSession.ConnectionResult\n"
        "import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder\n",
        1)

# qualify any bare ConnectionResult usages (defensive)
s = s.replace("): ConnectionResult {", "): MediaSession.ConnectionResult {")
s = s.replace("ConnectionResult.DEFAULT_SESSION_COMMANDS",
              "MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS")
s = s.replace("ConnectionResult.AcceptedResultBuilder(session)",
              "MediaSession.ConnectionResult.AcceptedResultBuilder(session)")

p.write_text(s)
print("PlaybackService.kt patched")
PY

git add -A
git commit -qm "fix(P2-3): media3 1.5.1 API — addNotificationActions returns IntArray; ConnectionResult is nested"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
