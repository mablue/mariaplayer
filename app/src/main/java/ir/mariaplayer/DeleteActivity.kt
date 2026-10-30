package ir.mariaplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import ir.mariaplayer.playback.PlaybackService

class DeleteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (uriStr == null) { finish(); return }
        val uri = Uri.parse(uriStr)

        val folder = queryFolder(uri)
        val msg = buildString {
            append("\"$title\"")
            if (folder.isNotBlank()) append("\n\nFolder: ").append(folder)
            append("\n\nThis permanently deletes the file from this device.")
            append(" It cannot be undone.")
        }

        AlertDialog.Builder(this)
            .setTitle("Delete music file?")
            .setMessage(msg)
            .setPositiveButton("Delete permanently") { _, _ -> proceedDelete(uri, title) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun queryFolder(uri: Uri): String = try {
        val col = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.RELATIVE_PATH
        else
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.getColumnCount() > 0) c.getString(0) ?: "" else ""
        } ?: ""
    } catch (_: Exception) { "" }

    private fun proceedDelete(uri: Uri, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                    notifyDeleted(uri)
                }
                finish()
            }
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            try {
                contentResolver.delete(uri, null, null)
                Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                notifyDeleted(uri)
            } catch (e: Exception) {
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun notifyDeleted(uri: Uri) {
        sendBroadcast(
            Intent(PlaybackService.ACTION_TRACK_DELETED)
                .setPackage(packageName)
                .putExtra(PlaybackService.EXTRA_DELETED_URI, uri.toString())
        )
    }

    companion object {
        const val EXTRA_URI = "delete_uri"
        const val EXTRA_TITLE = "delete_title"
    }
}
