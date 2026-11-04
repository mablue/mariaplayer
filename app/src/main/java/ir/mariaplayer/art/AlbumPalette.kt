package ir.mariaplayer.art

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette

/**
 * PR-L56: Palette-derived theme.
 *
 * LocalAlbumPalette is null when the current track has no cover art.
 * Theme.kt uses it if non-null, otherwise falls back to dynamic / default.
 */
val LocalAlbumPalette = compositionLocalOf<ColorScheme?> { null }

fun paletteFromBitmap(bmp: Bitmap?): ColorScheme? {
    if (bmp == null) return null
    val palette = runCatching {
        Palette.from(bmp).maximumColorCount(16).generate()
    }.getOrNull() ?: return null

    val swatch = palette.vibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.dominantSwatch
        ?: return null

    val accentRgb = swatch.rgb
    val accent = Color(accentRgb)
    val accentDeep = Color(blend(accentRgb, 0xFF080910.toInt(), 0.55f))
    val accentSoft = Color(blend(accentRgb, 0xFFF5F5F5.toInt(), 0.55f))

    return darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentDeep,
        onPrimaryContainer = Color.White,
        secondary = accentSoft,
        onSecondary = Color.Black,
        secondaryContainer = accentDeep,
        onSecondaryContainer = Color.White,
        tertiary = accentSoft,
        onTertiary = Color.Black,
        background = Color(0xFF0A0A0A),
        onBackground = Color(0xFFF5F5F5),
        surface = Color(0xFF101010),
        onSurface = Color(0xFFF5F5F5),
        surfaceVariant = accent.copy(alpha = 0.15f),
        onSurfaceVariant = Color(0xFFF5F5F5).copy(alpha = 0.72f),
        outline = accent.copy(alpha = 0.5f),
        outlineVariant = accent.copy(alpha = 0.25f),
        error = Color(0xFFCF6679),
        onError = Color.White,
    )
}

private fun blend(from: Int, to: Int, ratio: Float): Int {
    fun ch(fromShift: Int, toShift: Int): Int {
        val a = (from shr fromShift) and 0xFF
        val b = (to shr toShift) and 0xFF
        return ((a * (1 - ratio) + b * ratio).toInt().coerceIn(0, 255))
    }
    val r = ch(16, 16)
    val g = ch(8, 8)
    val b = ch(0, 0)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
