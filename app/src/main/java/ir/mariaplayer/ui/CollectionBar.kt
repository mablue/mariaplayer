package ir.mariaplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.mariaplayer.R
import ir.mariaplayer.data.Collection

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionBar(
    collections: List<Collection>,
    selectedId: String?,
    onSelect: (Collection) -> Unit,
    onAdd: () -> Unit,
    onLongPress: (Collection) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(collections, key = { it.id }) { c ->
            val selected = c.id == selectedId
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .combinedClickable(
                        onClick = { onSelect(c) },
                        onLongClick = { onLongPress(c) },
                    ),
            ) {
                Text(
                    text = c.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        item {
            IconButton(onClick = onAdd) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "New collection",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
