package ir.mariaplayer.playback

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.ResumeState
import ir.mariaplayer.util.PlaybackDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PR-L59 (SSOT): single source of truth for playback state.
 * 1 listener + 1 poller + 1 StateFlow, replacing per-screen duplicates.
 */
data class PlaybackSnapshot(
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
)

val LocalPlaybackRepository = compositionLocalOf<PlaybackRepository?> { null }

class PlaybackRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var listener: Player.Listener? = null
    private var pollerJob: Job? = null
    private var prevTrackId: Long? = null
    private var prevPositionMs: Long = 0L

    fun bind(newController: MediaController) {
        if (controller === newController) return
        unbind()
        controller = newController
        prevTrackId = newController.currentMediaItem?.mediaId?.toLongOrNull()
        prevPositionMs = newController.currentPosition

        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val c = controller ?: return
                prevPositionMs = c.currentPosition
                updateSnapshot(c)
                persistPause(c)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                controller?.let { updateSnapshot(it) }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val c = controller ?: return
                handleTransition(c, reason)
                updateSnapshot(c)
            }
            override fun onPlaybackStateChanged(state: Int) {
                val c = controller ?: return
                handleState(c, state)
                updateSnapshot(c)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                controller?.let {
                    prevPositionMs = it.currentPosition
                    updateSnapshot(it)
                }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                controller?.let { updateSnapshot(it) }
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                controller?.let { updateSnapshot(it) }
            }
        }
        newController.addListener(l)
        PlaybackDiagnostics.listenerRegistrations.incrementAndGet()
        listener = l

        pollerJob = scope.launch {
            while (isActive) {
                delay(500)
                val c = controller ?: continue
                if (c.playbackState == Player.STATE_ENDED) continue
                prevPositionMs = c.currentPosition
                updateSnapshot(c)
                persistTick(c)
            }
        }

        updateSnapshot(newController)
    }

    fun unbind() {
        listener?.let { controller?.removeListener(it) }
        listener = null
        pollerJob?.cancel()
        pollerJob = null
        val c = controller
        controller = null
        if (c != null) {
            val tid = c.currentMediaItem?.mediaId?.toLongOrNull()
            if (tid != null) {
                val pos = c.currentPosition
                scope.launch {
                    PlaybackPrefs.save(context, tid, pos)
                    PlaybackPrefs.savePosition(context, tid, pos)
                }
            }
        }
    }

    fun dispose() {
        unbind()
        scope.cancel()
    }

    suspend fun readLastSession(): ResumeState = PlaybackPrefs.read(context)

    private fun updateSnapshot(c: MediaController) {
        _state.value = PlaybackSnapshot(
            trackId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            title = c.mediaMetadata.title?.toString() ?: "",
            artist = c.mediaMetadata.artist?.toString() ?: "",
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.coerceAtLeast(0L),
            hasPrev = c.hasPreviousMediaItem(),
            hasNext = c.hasNextMediaItem(),
        )
    }

    private fun persistTick(c: MediaController) {
        val tid = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val pos = c.currentPosition
        scope.launch { PlaybackPrefs.save(context, tid, pos) }
    }

    private fun persistPause(c: MediaController) {
        val tid = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val pos = c.currentPosition
        scope.launch {
            PlaybackPrefs.save(context, tid, pos)
            PlaybackPrefs.savePosition(context, tid, pos)
        }
    }

    private fun handleTransition(c: MediaController, reason: Int) {
        val oldId = prevTrackId
        val oldPos = prevPositionMs
        when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                if (oldId != null) {
                    scope.launch {
                        PlaybackPrefs.clearPosition(context, oldId)
                        PlaybackPrefs.save(context, oldId, 0L)
                    }
                }
            }
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                if (oldId != null && oldPos > 5_000L) {
                    scope.launch {
                        PlaybackPrefs.savePosition(context, oldId, oldPos)
                    }
                }
            }
        }
        val newId = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (newId != null && newId != oldId) {
            scope.launch {
                val saved = PlaybackPrefs.readPosition(context, newId)
                if (saved != null && saved > 5_000L) {
                    controller?.seekTo(saved)
                }
            }
        }
        prevTrackId = newId
        prevPositionMs = c.currentPosition
    }

    private fun handleState(c: MediaController, state: Int) {
        val tid = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        when (state) {
            Player.STATE_READY -> {
                prevPositionMs = c.currentPosition
                scope.launch {
                    PlaybackPrefs.save(context, tid, c.currentPosition)
                    PlaybackPrefs.savePosition(context, tid, c.currentPosition)
                }
            }
            Player.STATE_ENDED -> {
                prevPositionMs = 0L
                scope.launch {
                    PlaybackPrefs.clearPosition(context, tid)
                    PlaybackPrefs.save(context, tid, 0L)
                }
            }
        }
    }
}

@Composable
fun rememberPlaybackRepository(controller: MediaController?): PlaybackRepository {
    val context = LocalContext.current
    val repository = remember { PlaybackRepository(context) }
    DisposableEffect(controller) {
        if (controller != null) repository.bind(controller)
        onDispose { repository.unbind() }
    }
    DisposableEffect(Unit) {
        onDispose { repository.dispose() }
    }
    return repository
}
