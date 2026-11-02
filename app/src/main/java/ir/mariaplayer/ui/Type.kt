package ir.mariaplayer.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ir.mariaplayer.R

/**
 * PR-L16a: Vazirmatn as default family for both Latin and Persian.
 * MIT licensed, designed for correct Persian shaping and ZWNJ layout.
 */
val VazirFamily = FontFamily(
    Font(R.font.vazir_regular, FontWeight.Normal),
    Font(R.font.vazir_bold, FontWeight.Bold),
)

private fun TextStyle.vazir() = copy(fontFamily = VazirFamily)

val VazirTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.vazir(),
        displayMedium = displayMedium.vazir(),
        displaySmall = displaySmall.vazir(),
        headlineLarge = headlineLarge.vazir(),
        headlineMedium = headlineMedium.vazir(),
        headlineSmall = headlineSmall.vazir(),
        titleLarge = titleLarge.vazir(),
        titleMedium = titleMedium.vazir(),
        titleSmall = titleSmall.vazir(),
        bodyLarge = bodyLarge.vazir(),
        bodyMedium = bodyMedium.vazir(),
        bodySmall = bodySmall.vazir(),
        labelLarge = labelLarge.vazir(),
        labelMedium = labelMedium.vazir(),
        labelSmall = labelSmall.vazir(),
    )
}
