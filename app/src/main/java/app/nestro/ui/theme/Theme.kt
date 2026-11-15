package app.nestro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NestroDarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Background,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    error = Danger
)

@Composable
fun NestroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NestroDarkColors,
        content = content
    )
}
