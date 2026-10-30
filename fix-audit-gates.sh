#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

mkdir -p .bak/audit-gates
cp app/src/main/java/ir/mariaplayer/MainActivity.kt               .bak/audit-gates/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/DeleteActivity.kt             .bak/audit-gates/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt   .bak/audit-gates/ 2>/dev/null || true
cp LESSONS.md                                                     .bak/audit-gates/ 2>/dev/null || true

# ── MainActivity: Omega-14 incremental delete, Gamma-14 position save ──
python3 - << 'PY'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
s = p.read_text()

old = """    var reloadKey by remember { mutableIntStateOf(0) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteTitle by remember { mutableStateOf("") }
"""
new = """    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteTitle by remember { mutableStateOf("") }

    LaunchedEffect(hasAudio) {
        if (hasAudio) tracks = LibraryScanner.scan(context)
    }
"""
if old not in s:
    print("FAIL: reloadKey block not found")
    sys.exit(1)
s = s.replace(old, new, 1)

old = """                if (i?.action == ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED) {
                    reloadKey++
                }"""
new = """                if (i?.action == ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED) {
                    val deletedUri = i.getStringExtra(
                        ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI
                    )
                    if (deletedUri != null) {
                        tracks = tracks?.filterNot { it.contentUri == deletedUri }
                    }
                }"""
if old not in s:
    print("FAIL: receiver reloadKey++ not found")
    sys.exit(1)
s = s.replace(old, new, 1)

old = """    val tracks by produceState<List<Track>?>(initialValue = null, hasAudio, reloadKey) {
        value = LibraryScanner.scan(context)
    }
"""
new = """    // tracks populated by LaunchedEffect above; deleted items removed in receiver
"""
if old not in s:
    print("FAIL: produceState(tracks) block not found")
    sys.exit(1)
s = s.replace(old, new, 1)

old = """    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            val mi = c.currentMediaItem ?: continue
            val tid = mi.mediaId.toLongOrNull() ?: continue
            PlaybackPrefs.save(context, tid, c.currentPosition)
        }
    }"""
new = """    // Gamma-14: save position every 500ms plus on every play/pause and READY/ENDED.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            delay(500)
            val mi = c.currentMediaItem ?: continue
            val tid = mi.mediaId.toLongOrNull() ?: continue
            PlaybackPrefs.save(context, tid, c.currentPosition)
        }
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val mi = c.currentMediaItem ?: return
                val tid = mi.mediaId.toLongOrNull() ?: return
                scope.launch { PlaybackPrefs.save(context, tid, c.currentPosition) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    val mi = c.currentMediaItem ?: return
                    val tid = mi.mediaId.toLongOrNull() ?: return
                    scope.launch { PlaybackPrefs.save(context, tid, c.currentPosition) }
                }
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }"""
if old not in s:
    print("FAIL: 3000ms ticker not found")
    sys.exit(1)
s = s.replace(old, new, 1)

p.write_text(s)
print("MainActivity: Omega-14 incremental delete, Gamma-14 position save")
PY

# ── DeleteActivity: Omega-16 no silent catch ──
python3 - << 'PY'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/DeleteActivity.kt")
s = p.read_text()

old = """        try {
            val rows = contentResolver.delete(uri, null, null)
            if (rows > 0) {
                Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                notifyDeleted(uri)
            }
        } catch (_: Exception) { }
        finish()"""
new = """        try {
            val rows = contentResolver.delete(uri, null, null)
            if (rows > 0) {
                Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                notifyDeleted(uri)
            } else {
                Toast.makeText(this, "Delete failed - no rows", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.util.Log.w("DeleteActivity", "direct delete failed for $uri", e)
            Toast.makeText(this, "Delete failed: " + e.message, Toast.LENGTH_LONG).show()
        }
        finish()"""
if old not in s:
    print("FAIL: silent catch in tryDirectDelete not found")
    sys.exit(1)
s = s.replace(old, new, 1)
p.write_text(s)
print("DeleteActivity: Omega-16 no silent catch")
PY

# ── PlaybackService: Omega-16 + Lambda-28 typed errors ──
python3 - << 'PY'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt")
s = p.read_text()

old = """            for (i in toRemove.sortedDescending()) {
                try { player.removeMediaItem(i) } catch (_: Exception) {}
            }"""
new = """            for (i in toRemove.sortedDescending()) {
                try { player.removeMediaItem(i) }
                catch (e: Exception) {
                    android.util.Log.w("PlaybackService", "removeMediaItem($i) failed", e)
                }
            }"""
if old not in s:
    print("FAIL: silent catch in deleteReceiver not found")
    sys.exit(1)
s = s.replace(old, new, 1)

old = """        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCustomLayout()
            }
        })
"""
new = """        player.addListener(object : Player.Listener {
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
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
            }
        })
"""
if old not in s:
    print("FAIL: player.addListener(onMediaItemTransition) block not found")
    sys.exit(1)
s = s.replace(old, new, 1)

p.write_text(s)
print("PlaybackService: Omega-16 no silent catch, Lambda-28 typed errors")
PY

cat >> LESSONS.md << 'EOF'

## 2026-10-30 audit — spec compliance gaps found after device confirm
- tried: ship P2/P3 as done without running the SPD.MUSIC_PLAYER audit
- why-failed: three code-level violations:
  Omega-14: delete re-scanned ALL tracks via produceState(reloadKey)
  Omega-16: tryDirectDelete + removeMediaItem had empty catch { }
  Gamma-14: PlaybackPrefs ticker was 3000ms -> up to 3s drift on kill
  Lambda-28: no typed PlaybackError -> generic or silent playback failures
- learned: run the audit BEFORE declaring next phase done. Gamma-gates are
  cheap to check; violating them after user says "works" wastes user trust.
  Fixes: incremental list removal on delete broadcast; 500ms ticker + save
  on onIsPlayingChanged + READY/ENDED; typed PlaybackException with toast +
  auto-skip; no catch(_: Exception) {} anywhere that mutates state or files.
- fixed-by: this commit
EOF

git add -A
git commit -qm "fix(gates): Omega-14 incremental delete, Omega-16 no silent catch, Gamma-14 position save, Lambda-28 typed playback errors"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
