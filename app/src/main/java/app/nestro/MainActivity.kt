package app.nestro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.nestro.ui.NestroApp
import app.nestro.ui.theme.NestroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NestroTheme {
                NestroApp()
            }
        }
    }
}
