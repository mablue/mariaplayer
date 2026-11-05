package ir.mariaplayer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import ir.mariaplayer.art.LocalAlbumPalette

// Fallback brand colors if Dynamic Color is not available (Android < 12)
val FallbackPrimary = androidx.compose.ui.graphics.Color(0xFF7928F5)

// ─── Legacy brand colors (used by GlassCard, Playlists*, PlaylistDetail) ───
// These will be migrated to MaterialTheme.colorScheme in stages 2-3 and
// removed once all call sites use the dynamic palette.
val VioletPrimary = androidx.compose.ui.graphics.Color(0xFF7928F5)
val VioletDeep    = androidx.compose.ui.graphics.Color(0xFF4A1799)
val IvoryWhite    = androidx.compose.ui.graphics.Color(0xFFFFFDF8)
val PetalPink     = androidx.compose.ui.graphics.Color(0xFFF2DCEB)
val DeepBlack     = androidx.compose.ui.graphics.Color(0xFF080910)

private val FallbackDark = darkColorScheme(primary = FallbackPrimary)
private val FallbackLight = lightColorScheme(primary = FallbackPrimary)

@Composable
fun MariaPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val coverPalette = LocalAlbumPalette.current
    val colorScheme = when {
        coverPalette != null -> coverPalette
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VazirTypography,
        content = content
    )
}
