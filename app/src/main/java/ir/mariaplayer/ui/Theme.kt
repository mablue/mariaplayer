package ir.mariaplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
        content = content,
    )
}
