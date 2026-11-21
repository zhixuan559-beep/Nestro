package app.nestro

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.nestro.ui.NestroApp
import app.nestro.ui.theme.NestroTheme

class MainActivity : ComponentActivity() {
    private var notificationDestination by mutableStateOf("home")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationDestination = intent.getStringExtra("open_page")?.takeIf { it == "calendar" } ?: "home"
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            NestroTheme {
                NestroApp(initialPage = notificationDestination)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination = intent.getStringExtra("open_page")?.takeIf { it == "calendar" } ?: "home"
    }
}
