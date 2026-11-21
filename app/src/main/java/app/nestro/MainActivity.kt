package app.nestro

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.nestro.model.ModelScopeOAuthCallback
import app.nestro.ui.NestroApp
import app.nestro.ui.theme.NestroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        handleModelScopeOAuthCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination = intent.getStringExtra("open_page")?.takeIf { it == "calendar" } ?: "home"
        handleModelScopeOAuthCallback(intent)
    }

    private fun handleModelScopeOAuthCallback(source: Intent?) {
        val callback = source?.data ?: return
        if (callback.scheme != "nestro" || callback.host != "oauth" || callback.path != "/modelscope") return
        if (BuildConfig.MODELSCOPE_OAUTH_BACKEND_URL.isBlank()) {
            Toast.makeText(this, "尚未配置魔搭授权服务地址", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ModelScopeOAuthCallback.complete(applicationContext, callback, BuildConfig.MODELSCOPE_OAUTH_BACKEND_URL)
                }
            }.onSuccess { account ->
                Toast.makeText(this@MainActivity, "魔搭账号已连接：${account.displayName}，请到模型管理刷新模型目录", Toast.LENGTH_LONG).show()
                recreate()
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, error.message ?: "魔搭授权失败，请重新连接", Toast.LENGTH_LONG).show()
            }
        }
    }
}
