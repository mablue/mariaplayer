package ir.mariaplayer.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ir.mariaplayer.R

/**
 * Mikhak by Amin Abedi (SIL OFL 1.1). Handwritten feel with matching
 * Latin glyphs — designed as one family for Persian + English.
 */
val MikhakFamily = FontFamily(
    Font(R.font.mikhak_regular, FontWeight.Normal),
    Font(R.font.mikhak_bold, FontWeight.Bold),
)

private fun TextStyle.mikhak() = copy(fontFamily = MikhakFamily)

val MikhakTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.mikhak(),
        displayMedium = displayMedium.mikhak(),
        displaySmall = displaySmall.mikhak(),
        headlineLarge = headlineLarge.mikhak(),
        headlineMedium = headlineMedium.mikhak(),
        headlineSmall = headlineSmall.mikhak(),
        titleLarge = titleLarge.mikhak(),
        titleMedium = titleMedium.mikhak(),
        titleSmall = titleSmall.mikhak(),
        bodyLarge = bodyLarge.mikhak(),
        bodyMedium = bodyMedium.mikhak(),
        bodySmall = bodySmall.mikhak(),
        labelLarge = labelLarge.mikhak(),
        labelMedium = labelMedium.mikhak(),
        labelSmall = labelSmall.mikhak(),
    )
}
