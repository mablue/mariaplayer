package ir.mariaplayer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.mariaplayer.R

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }

    fun openStore(scheme: String, web: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(scheme)))
        } catch (_: Exception) {
            openUrl(web)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("\u0628\u0633\u062a\u0646") }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "MariaPlayer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "\u0646\u0633\u062e\u0647 ${ir.mariaplayer.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    "\u0645\u0633\u0639\u0648\u062f \u0639\u0632\u06cc\u0632\u06cc",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Masoud Azizi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Computer Science\nUniversity of Tabriz\nTabriz, Iran",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(16.dp))

                LinkRow("\u0627\u06cc\u0645\u06cc\u0644", "mablue92@gmail.com") {
                    openUrl("mailto:mablue92@gmail.com")
                }
                LinkRow("\u0648\u0628\u200c\u0633\u0627\u06cc\u062a", "masoudsoft.ir") {
                    openUrl("https://masoudsoft.ir")
                }
                LinkRow("GitHub", "github.com/mablue") {
                    openUrl("https://github.com/mablue")
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    "\u062d\u0645\u0627\u06cc\u062a \u0627\u0632 \u0645\u0627",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        8.dp, Alignment.CenterHorizontally
                    ),
                ) {
                    OutlinedButton(onClick = {
                        openStore(
                            "bazaar://details?id=ir.mariaplayer",
                            "https://cafebazaar.ir/app/ir.mariaplayer",
                        )
                    }) { Text("\u0628\u0627\u0632\u0627\u0631") }
                    OutlinedButton(onClick = {
                        openStore(
                            "myket://details?id=ir.mariaplayer",
                            "https://myket.ir/app/ir.mariaplayer",
                        )
                    }) { Text("\u0645\u0627\u06cc\u06a9\u062a") }
                }
            }
        },
    )
}

@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        TextButton(onClick = onClick) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
