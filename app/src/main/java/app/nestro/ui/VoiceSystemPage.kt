package app.nestro.ui

import android.content.Context
import app.nestro.speech.AndroidTextToSpeechController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun VoiceSystemPage() {
    val context = LocalContext.current
    var speechText by remember { mutableStateOf("你好，欢迎使用 Nestro 语音系统。") }
    var speechRate by remember { mutableStateOf(1f) }
    var pitch by remember { mutableStateOf(1f) }
    var selectedLanguage by remember { mutableStateOf(Locale.SIMPLIFIED_CHINESE) }
    var availableLanguages by remember { mutableStateOf<Set<Locale>>(emptySet()) }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在初始化本机语音合成…") }

    val controller = remember(context) {
        AndroidTextToSpeechController(context, object : AndroidTextToSpeechController.Listener {
            override fun onReady(availableLanguages: Set<Locale>) {
                ready = true
                this@VoiceSystemPage.run { }
                status = if (availableLanguages.isEmpty()) {
                    "语音引擎已就绪；语言列表由系统引擎提供。"
                } else {
                    "语音引擎已就绪，检测到 ${availableLanguages.size} 种语言。"
                }
            }

            override fun onStarted(utteranceId: String) {
                status = "正在朗读…"
            }

            override fun onCompleted(utteranceId: String) {
                status = "朗读完成"
            }

            override fun onError(utteranceId: String, message: String) {
                status = message
            }
        })
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("语音输入与输出")
        Text("语音输入：在聊天输入框按住麦克风说话，松开后由 Android 系统识别服务转写并发送。识别服务的数据处理方式取决于设备当前启用的系统语音服务。")
        Text("语音输出：使用设备已安装的 Android 语音合成引擎在本机朗读，不会通过本功能上传文本。")
        OutlinedTextField(
            value = speechText,
            onValueChange = { speechText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("朗读内容（最多 4000 字符）"),
            },
            minLines = 3
        )
        Text("朗读语言")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selectedLanguage = Locale.SIMPLIFIED_CHINESE }) {
                Text("中文")
            }
            OutlinedButton(onClick = { selectedLanguage = Locale.US }) {
                Text("English")
            }
        }
        Text("语速：${"%.1f".format(Locale.ROOT, speechRate)}")
        Slider(value = speechRate, onValueChange = { speechRate = it }, valueRange = 0.5f..2f)
        Text("音调：${"%.1f".format(Locale.ROOT, pitch)}")
        Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = ready,
                onClick = {
                    if (controller.speak(speechText, selectedLanguage, speechRate, pitch)) {
                        status = "已提交朗读任务…"
                    }
                }
            ) { Text("开始朗读") }
            OutlinedButton(
                enabled = ready,
                onClick = {
                    controller.stop()
                    status = "已停止朗读"
                }
            ) { Text("停止") }
        }
        Text(status)
    }
}
