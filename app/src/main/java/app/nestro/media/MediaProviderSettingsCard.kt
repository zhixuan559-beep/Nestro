package app.nestro.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaProviderSettingsCard() {
    val context = LocalContext.current
    val store = remember(context) { MediaProviderConfigStore(context) }
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf("自定义多模态服务") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var transcriptionPath by remember { mutableStateOf("audio/transcriptions") }
    var scenePath by remember { mutableStateOf("media/scenes") }
    var audioPath by remember { mutableStateOf("audio/process") }
    var configured by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("服务配置保存在本机；API Key 使用 Android Keystore 加密。") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        val existing = withContext(Dispatchers.IO) { store.read() }
        if (existing != null) {
            displayName = existing.displayName
            baseUrl = existing.baseUrl
            model = existing.model
            transcriptionPath = existing.transcriptionPath
            scenePath = existing.scenePath
            audioPath = existing.audioProcessPath
            configured = true
            MediaIntelligenceRegistry.register(OpenAiCompatibleMediaIntelligenceProvider(existing))
            MediaIntelligenceRegistry.activate(existing.providerId)
            status = "已载入本机媒体服务配置："+existing.displayName+"。"
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("媒体 AI 服务商", style = MaterialTheme.typography.titleMedium)
            Text("仅在你主动确认媒体分析时，才会把素材发送到这里。转写接口使用 OpenAI 兼容格式；场景识别和音频处理接口路径可按服务商文档调整。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(displayName, { displayName = it }, label = { Text("服务商名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("HTTPS 服务地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (configured) "新 API Key（留空不允许覆盖）" else "API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(transcriptionPath, { transcriptionPath = it }, label = { Text("转写接口路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(scenePath, { scenePath = it }, label = { Text("场景识别接口路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(audioPath, { audioPath = it }, label = { Text("音频处理接口路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(
                enabled = !busy && apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val config = MediaProviderConfig(
                                    displayName = displayName,
                                    baseUrl = baseUrl,
                                    apiKey = apiKey,
                                    model = model,
                                    transcriptionPath = transcriptionPath,
                                    scenePath = scenePath,
                                    audioProcessPath = audioPath
                                )
                                config.validate()
                                store.save(config)
                                config
                            }
                        }
                        result.onSuccess { config ->
                            MediaIntelligenceRegistry.register(OpenAiCompatibleMediaIntelligenceProvider(config))
                            MediaIntelligenceRegistry.activate(config.providerId)
                            configured = true
                            apiKey = ""
                            status = "媒体服务已保存并启用。密钥不会显示或写入普通日志。"
                        }.onFailure { status = "保存失败："+(it.message ?: "配置无效") }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "处理中…" else "保存并启用媒体服务") }
            if (configured) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { store.delete() } }
                            if (result.isSuccess) {
                                MediaIntelligenceRegistry.unregister("openai-compatible-media")
                                configured = false
                                status = "媒体服务配置已删除。"
                            } else status = "删除失败："+(result.exceptionOrNull()?.message ?: "未知错误")
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("删除本机媒体服务配置") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
