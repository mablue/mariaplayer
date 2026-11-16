package ir.mariaplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.mariaplayer.data.ContentType

@Composable
fun ContentTypeFilterBar(
    selected: ContentType?,
    onChange: (ContentType?) -> Unit,
    counts: Map<ContentType?, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val options = listOf<Pair<ContentType?, String>>(
        null to "All",
        ContentType.MUSIC to "Music",
        ContentType.PODCAST to "Podcasts",
        ContentType.AUDIOBOOK to "Audiobooks",
    )
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { pair ->
            val (type, label) = pair
            val count = counts[type]
            val display = if (count != null) "$label ($count)" else label
            FilterChip(
                selected = selected == type,
                onClick = { onChange(type) },
                label = { Text(display) },
            )
        }
    }
}
