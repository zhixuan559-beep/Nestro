package app.nestro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val NostColors = lightColorScheme(
    primary = Mint,
    onPrimary = Background,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    error = Danger
)

@Composable
fun NestroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NostColors,
        content = content
    )
}
