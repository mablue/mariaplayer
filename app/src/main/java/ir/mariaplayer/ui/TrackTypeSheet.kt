package ir.mariaplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.mariaplayer.data.ContentType
import ir.mariaplayer.data.ContentTypePrefs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackTypeSheet(
    trackIds: List<Long>,
    onDismiss: () -> Unit,
) {
    if (trackIds.isEmpty()) { onDismiss(); return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                if (trackIds.size == 1) "Change type" else "Change type (${trackIds.size} tracks)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ContentType.values().forEach { type ->
                ListItem(
                    headlineContent = { Text(type.label) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            for (id in trackIds) ContentTypePrefs.set(context, id, type)
                        }
                        onDismiss()
                    },
                )
                HorizontalDivider()
            }
            Column(Modifier.padding(top = 16.dp)) {}
        }
    }
}
