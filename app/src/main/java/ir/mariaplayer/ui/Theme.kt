package ir.mariaplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Legacy brand aliases — kept only so existing call sites
// (GlassCard, PlaylistsScreen, PlaylistDetailScreen) compile.
// They are NOT wired into MaterialTheme.colorScheme. Stage-4 cleanup
// will replace them with MaterialTheme.colorScheme equivalents.
val VioletPrimary = androidx.compose.ui.graphics.Color(0xFF7928F5)
val VioletDeep    = androidx.compose.ui.graphics.Color(0xFF4A1799)
val IvoryWhite    = androidx.compose.ui.graphics.Color(0xFFFFFDF8)
val PetalPink     = androidx.compose.ui.graphics.Color(0xFFF2DCEB)
val DeepBlack     = androidx.compose.ui.graphics.Color(0xFF080910)

private val StandardDark  = darkColorScheme()
private val StandardLight = lightColorScheme()

@Composable
fun MariaPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) StandardDark else StandardLight
    MaterialTheme(
        colorScheme = colorScheme,
        typography = VazirTypography,
        content = content
    )
}
