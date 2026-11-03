package ir.mariaplayer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

@Composable
fun MariaPlayerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31          -> dynamicLightColorScheme(ctx)
        dark                                 -> darkColorScheme()
        else                                 -> lightColorScheme()
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = VazirTypography,
        content = {
            // PR-L33: preserve the inherited TextStyle (bodyLarge from
            // Material3 typography, which already has VazirFamily set via
            // VazirTypography). A bare TextStyle(fontFamily = ...) dropped
            // fontSize/weight defaults → every Text() fell to 14sp.
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(
                    fontFamily = VazirFamily
                ),
                content = content,
            )
        },
    )
}
