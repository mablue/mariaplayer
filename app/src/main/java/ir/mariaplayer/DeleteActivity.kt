package ir.mariaplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import ir.mariaplayer.playback.ACTION_TRACK_DELETED
import ir.mariaplayer.playback.EXTRA_DELETED_URI
import ir.mariaplayer.playback.EXTRA_DELETED_TRACK_ID

class DeleteActivity : ComponentActivity() {

    private var pendingUri: Uri? = null
    private var pendingTitle: String = ""
    private var pendingTrackId: Long? = null

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingUri
        val title = pendingTitle
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
            notifyDeleted(uri)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val tid = intent.getLongExtra(EXTRA_DELETED_TRACK_ID, -1L)
        if (uriStr == null) { finish(); return }

        val uri = Uri.parse(uriStr)
        pendingUri = uri
        pendingTitle = title
        pendingTrackId = if (tid > 0L) tid else null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (_: Exception) {
                tryDirectDelete(uri, title)
            }
        } else {
            tryDirectDelete(uri, title)
        }
    }

    private fun tryDirectDelete(uri: Uri, title: String) {
        try {
            val rows = contentResolver.delete(uri, null, null)
            if (rows > 0) {
                Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                notifyDeleted(uri)
            } else {
                Toast.makeText(this, "Delete failed - no rows", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            // PR-L7e: Android 10 (Q) throws RecoverableSecurityException for
            // media deletes; user must consent via IntentSender. The launcher
            // callback calls finish(), so return without falling through.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && handleRecoverableDelete(e)) return

            android.util.Log.w("DeleteActivity", "direct delete failed for $uri", e)
            Toast.makeText(this, "Delete failed: " + e.message, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun handleRecoverableDelete(e: Exception): Boolean {
        if (e !is android.app.RecoverableSecurityException) return false
        return try {
            deleteLauncher.launch(
                IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
            )
            true
        } catch (_: Exception) { false }
    }

    private fun notifyDeleted(uri: Uri) {
        val intent = Intent(ACTION_TRACK_DELETED)
            .setPackage(packageName)
            .putExtra(EXTRA_DELETED_URI, uri.toString())
        pendingTrackId?.let { intent.putExtra(EXTRA_DELETED_TRACK_ID, it) }
        sendBroadcast(intent)
    }

    companion object {
        const val EXTRA_URI = "delete_uri"
        const val EXTRA_TITLE = "delete_title"
    }
}
