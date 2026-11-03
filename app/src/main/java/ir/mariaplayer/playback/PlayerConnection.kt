package ir.mariaplayer.playback

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

@Composable
fun rememberPlayerController(): MediaController? {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var disposed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val token = SessionToken(
            ctx,
            ComponentName(ctx, PlaybackService::class.java),
        )
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener(
            {
                val c = try { future.get() } catch (_: Throwable) { null }
                // PR-L33: if the composable was disposed before the async
                // build finished, release the late-arriving controller so
                // we don't leak a MediaSession connection.
                if (disposed) c?.release() else controller = c
            },
            MoreExecutors.directExecutor(),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            controller?.release()
            controller = null
        }
    }

    return controller
}
