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

    LaunchedEffect(Unit) {
        val token = SessionToken(
            ctx,
            ComponentName(ctx, PlaybackService::class.java),
        )
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener(
            { controller = try { future.get() } catch (_: Throwable) { null } },
            MoreExecutors.directExecutor(),
        )
    }

    DisposableEffect(Unit) {
        onDispose { controller?.release() }
    }

    return controller
}
