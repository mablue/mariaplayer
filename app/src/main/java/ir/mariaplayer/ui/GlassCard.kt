package ir.mariaplayer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.mariaplayer.R
import ir.mariaplayer.art.AlbumArtCache
import ir.mariaplayer.data.Track

/**
 * M3-standard glass card. When [containerColor] is Unspecified, the container
 * resolves to a subtle surfaceVariant tint. Every color derives from
 * MaterialTheme.colorScheme.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = modifier.fillMaxWidth()
    val clickable = when {
        onClick != null && onLongClick != null ->
            base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        onClick != null -> base.clickable(onClick = onClick)
        else -> base
    }
    val resolvedColor = if (containerColor == Color.Unspecified) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    } else {
        containerColor
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = resolvedColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = clickable,
    ) {
        Box { content() }
    }
}

/** M3-standard track row. All colors derive from MaterialTheme.colorScheme. */
@Composable
fun GlassTrackRow(
    title: String,
    subtitle: String,
    isFavorite: Boolean,
    onFavClick: () -> Unit,
    onDeleteClick: (() -> Unit)?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    isActive: Boolean = false,
    track: Track? = null,
) {
    val context = LocalContext.current
    val art by produceState<Bitmap?>(
        initialValue = track?.let { AlbumArtCache.peek(it.id) },
        key1 = track?.id,
    ) {
        if (track != null && value == null) {
            value = AlbumArtCache.get(context, track.id, track.contentUri)
        }
    }
    GlassCard(
        onClick = onClick,
        onLongClick = onLongClick,
        containerColor = if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        } else {
            Color.Unspecified
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = art
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        title.take(1).uppercase().ifBlank { "♪" },
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onFavClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) R.drawable.ic_favorite_filled
                        else R.drawable.ic_favorite_border
                    ),
                    contentDescription =
                        if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            if (onDeleteClick != null) {
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "Delete track",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
