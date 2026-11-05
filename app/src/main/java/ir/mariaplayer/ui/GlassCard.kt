package ir.mariaplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import ir.mariaplayer.R
import ir.mariaplayer.art.AlbumArtCache
import ir.mariaplayer.data.Track

/**
 * PR-49: shared glass card, mirrors the look of the lyrics result cards.
 * Every list surface (library, playlists, playlist detail) should use it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.White.copy(alpha = 0.07f),
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        modifier = clickable,
    ) {
        Box { content() }
    }
}

/** PR-49: standard track row in glass style — used by library and playlists. */
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
        containerColor = if (isActive) VioletPrimary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.07f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(listOf(VioletPrimary, VioletDeep))
                    ),
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
                        color = IvoryWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = IvoryWhite,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = IvoryWhite.copy(alpha = 0.55f),
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
                    tint = if (isFavorite) VioletPrimary else IvoryWhite.copy(alpha = 0.55f),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (onDeleteClick != null) {
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "Delete track",
                        tint = IvoryWhite.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
