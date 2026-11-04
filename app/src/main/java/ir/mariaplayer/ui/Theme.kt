package ir.mariaplayer.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * PR-L45: MariaPlayer fixed brand theme.
 *
 * The four brand colors, nothing else:
 *   - Electric Violet   #7928F5  (primary action, active state)
 *   - Soft Ivory White  #FFFDF8  (main text, icons)
 *   - Pale Petal Pink   #F2DCEB  (subtle accent)
 *   - Deep Black        #080910  (background, base)
 *
 * No dynamic color. No light variant. The icon is the identity;
 * the theme mirrors it. Derived surfaces stay inside the violet
 * family to preserve recognisability.
 */
val VioletPrimary = Color(0xFF7928F5)
val IvoryWhite    = Color(0xFFFFFDF8)
val PetalPink     = Color(0xFFF2DCEB)
val DeepBlack     = Color(0xFF080910)

// Derived surface tones — strictly within the violet family
val VioletDeep    = Color(0xFF4A1799)
val VioletMuted   = Color(0xFF2A0D57)
val BlackSurface  = Color(0xFF14151C)

private val MariaColors = darkColorScheme(
    primary              = VioletPrimary,
    onPrimary            = IvoryWhite,
    primaryContainer     = VioletDeep,
    onPrimaryContainer   = IvoryWhite,
    inversePrimary       = VioletMuted,

    secondary            = PetalPink,
    onSecondary          = DeepBlack,
    secondaryContainer   = VioletMuted,
    onSecondaryContainer = PetalPink,

    tertiary             = PetalPink,
    onTertiary           = DeepBlack,

    background           = DeepBlack,
    onBackground         = IvoryWhite,

    surface              = DeepBlack,
    onSurface            = IvoryWhite,
    surfaceVariant       = BlackSurface,
    onSurfaceVariant     = IvoryWhite.copy(alpha = 0.72f),
    surfaceTint          = VioletPrimary,

    outline              = VioletMuted,
    outlineVariant       = VioletDeep,

    error                = Color(0xFFCF6679),
    onError              = IvoryWhite,
    errorContainer       = Color(0xFF5A1D24),
    onErrorContainer     = Color(0xFFFFDAD6),
)

@Composable
fun MariaPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MariaColors,
        typography = VazirTypography,
        content = {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(
                    fontFamily = VazirFamily
                ),
                content = content,
            )
        },
    )
}
