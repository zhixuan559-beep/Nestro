package app.nestro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun NestroTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color(0xFF111111),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFFAAAAAA),
            outline = Color(0xFF303030),
            error = Color(0xFFFF6B6B)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF111111),
            onPrimary = Color.White,
            background = Color.White,
            onBackground = Color(0xFF111111),
            surface = Color.White,
            onSurface = Color(0xFF111111),
            surfaceVariant = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFF6B6B6B),
            outline = Color(0xFFE5E5E7),
            error = Color(0xFFB42318)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
