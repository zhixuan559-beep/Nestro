package app.nestro.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaProviderSettingsCard() {
    val context = LocalContext.current
    val store = remember(context) { MediaProviderConfigStore(context) }
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf("openai-compatible-media") }
    var providerSummaries by remember { mutableStateOf(emptyList<MediaProviderSummary>()) }
    var displayName by remember { mutableStateOf("自定义多模态服务") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var transcriptionModel by remember { mutableStateOf("") }
    var transcriptionPath by remember { mutableStateOf("audio/transcriptions") }
    var scenePath by remember { mutableStateOf("") }
    var audioPath by remember { mutableStateOf("") }
    var imageModel by remember { mutableStateOf("") }
    var imagePath by remember { mutableStateOf("images/generations") }
    var videoModel by remember { mutableStateOf("") }
    var videoSubmitPath by remember { mutableStateOf("video/generations") }
    var videoStatusPath by remember { mutableStateOf("video/generations/{job_id}") }
    var videoModelField by remember { mutableStateOf("model") }
    var videoPromptField by remember { mutableStateOf("prompt") }
    var videoAuthHeader by remember { mutableStateOf("Authorization") }
    var videoAuthPrefix by remember { mutableStateOf("Bearer ") }
    var videoExtraRequestJson by remember { mutableStateOf("{}") }
    var videoJobIdField by remember { mutableStateOf("id") }
    var videoStatusField by remember { mutableStateOf("status") }
    var videoResultUrlField by remember { mutableStateOf("video_url") }
    var configured by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("服务配置保存在本机；API Key 使用 Android Keystore 加密。") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        providerSummaries = withContext(Dispatchers.IO) { store.list() }
        val existing = withContext(Dispatchers.IO) { store.read() }
        if (existing != null) {
            providerId = existing.providerId
            displayName = existing.displayName
            baseUrl = existing.baseUrl
            model = existing.model
            transcriptionModel = existing.transcriptionModel
            transcriptionPath = existing.transcriptionPath
            scenePath = existing.scenePath
            audioPath = existing.audioProcessPath
            imageModel = existing.imageGenerationModel
            imagePath = existing.imageGenerationPath
            videoModel = existing.videoGenerationModel
            videoSubmitPath = existing.videoSubmitPath
            videoStatusPath = existing.videoStatusPath
            videoModelField = existing.videoModelField
            videoPromptField = existing.videoPromptField
            videoAuthHeader = existing.videoAuthHeader
            videoAuthPrefix = existing.videoAuthPrefix
            videoExtraRequestJson = existing.videoExtraRequestJson
            videoJobIdField = existing.videoJobIdField
            videoStatusField = existing.videoStatusField
            videoResultUrlField = existing.videoResultUrlField
            configured = true
            MediaIntelligenceRegistry.register(OpenAiCompatibleMediaIntelligenceProvider(existing))
            MediaIntelligenceRegistry.activate(existing.providerId)
            status = "已载入本机媒体服务配置："+existing.displayName+"。"
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("媒体 AI 服务商", style = MaterialTheme.typography.titleMedium)
            Text("媒体服务商可只配置视频模型，不必同时填写聊天模型。聊天/视觉连接测试只验证对应聊天接口；视频生成协议会在首次真实任务时验证，可能产生费用。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={ providerId="openai"; displayName="OpenAI 官方 API"; baseUrl="https://api.openai.com/v1"; transcriptionPath="audio/transcriptions"; transcriptionModel="gpt-transcribe"; scenePath=""; audioPath=""; imageModel=""; imagePath="images/generations"; videoModel=""; videoSubmitPath="video/generations"; videoStatusPath="video/generations/{job_id}"; videoModelField="model"; videoPromptField="prompt"; videoAuthHeader="Authorization"; videoAuthPrefix="Bearer "; videoExtraRequestJson="{}"; videoJobIdField="id"; videoStatusField="status"; videoResultUrlField="video_url"; status="已填入 OpenAI 官方接口地址。请填写 API Key 和支持图像输入的聊天模型 ID 后测试。" },modifier=Modifier.fillMaxWidth()) { Text("填入 OpenAI 官方接口预设") }
            OutlinedTextField(providerId, { providerId = it }, label = { Text("服务商 ID（唯一）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(displayName, { displayName = it }, label = { Text("服务商名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("HTTPS 服务地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (configured) "新 API Key（留空不允许覆盖）" else "API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, label = { Text("图像理解聊天模型 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(transcriptionModel, { transcriptionModel = it }, label = { Text("音频转写模型 ID（OpenAI 官方默认 gpt-transcribe）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("gpt-transcribe 是官方推荐模型，但返回全文而非逐句时间戳；如需分段字幕，可暂用 whisper-1 的 segment 时间戳模式，该旧模型计划于 2027-02-26 停止服务。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(transcriptionPath, { transcriptionPath = it }, label = { Text("转写接口路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(scenePath, { scenePath = it }, label = { Text("场景识别接口路径（可选，需按服务商文档填写）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(audioPath, { audioPath = it }, label = { Text("音频处理接口路径（可选，需按服务商文档填写）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("图像生成（可选）：独立配置生图模型；接口按 OpenAI Images API 兼容格式提交，结果会保存为当前项目图片素材。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(imageModel, { imageModel = it }, label = { Text("图像生成模型 ID（留空表示不支持）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(imagePath, { imagePath = it }, label = { Text("图像生成接口路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("异步视频生成（可选）：提交任务后轮询状态，再下载成品。不同服务商的任务协议和字段名可能不同，请按服务商文档填写。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(videoModel, { videoModel = it }, label = { Text("视频生成模型 ID（留空表示不支持）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoSubmitPath, { videoSubmitPath = it }, label = { Text("视频任务提交路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoStatusPath, { videoStatusPath = it }, label = { Text("任务状态路径（必须包含 {job_id}）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoModelField, { videoModelField = it }, label = { Text("请求体中的模型字段名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoPromptField, { videoPromptField = it }, label = { Text("请求体中的提示词字段名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoAuthHeader, { videoAuthHeader = it }, label = { Text("视频 API 认证请求头") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoAuthPrefix, { videoAuthPrefix = it }, label = { Text("视频 API Key 前缀（Bearer 后留空格）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoExtraRequestJson, { videoExtraRequestJson = it.take(12_000) }, label = { Text("视频 API 额外请求参数 JSON（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(videoJobIdField, { videoJobIdField = it }, label = { Text("任务 ID 响应字段（支持 a.b）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoStatusField, { videoStatusField = it }, label = { Text("任务状态响应字段（支持 a.b）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(videoResultUrlField, { videoResultUrlField = it }, label = { Text("视频成品 URL 响应字段（支持 a.b）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(
                enabled = !busy && apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                MediaProviderConnectionTest.run(
                                    MediaProviderConfig(
                                        providerId = providerId,
                                        displayName = displayName,
                                        baseUrl = baseUrl,
                                        apiKey = apiKey,
                                        model = model,
                                        transcriptionModel = transcriptionModel,
                                        transcriptionPath = transcriptionPath,
                                        scenePath = scenePath,
                                        audioProcessPath = audioPath,
                                        imageGenerationModel = imageModel,
                                        imageGenerationPath = imagePath,
                                        videoGenerationModel = videoModel,
                                        videoSubmitPath = videoSubmitPath,
                                        videoStatusPath = videoStatusPath,
                                        videoModelField = videoModelField,
                                        videoPromptField = videoPromptField,
                                        videoAuthHeader = videoAuthHeader,
                                        videoAuthPrefix = videoAuthPrefix,
                                        videoExtraRequestJson = videoExtraRequestJson,
                                        videoJobIdField = videoJobIdField,
                                        videoStatusField = videoStatusField,
                                        videoResultUrlField = videoResultUrlField
                                    )
                                )
                            }
                        }
                        status = result.fold({ it }, { "连接测试失败："+(it.message ?: "服务不可用") })
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "测试中…" else "测试聊天/视觉模型连接") }
            Button(
                enabled = !busy && apiKey.isNotBlank() && baseUrl.isNotBlank() && (model.isNotBlank() || transcriptionModel.isNotBlank() || imageModel.isNotBlank() || videoModel.isNotBlank()),
                onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val config = MediaProviderConfig(
                                    providerId = providerId,
                                    displayName = displayName,
                                    baseUrl = baseUrl,
                                    apiKey = apiKey,
                                    model = model,
                                    transcriptionModel = transcriptionModel,
                                    transcriptionPath = transcriptionPath,
                                    scenePath = scenePath,
                                    audioProcessPath = audioPath,
                                        imageGenerationModel = imageModel,
                                        imageGenerationPath = imagePath,
                                        videoGenerationModel = videoModel,
                                        videoSubmitPath = videoSubmitPath,
                                        videoStatusPath = videoStatusPath,
                                        videoModelField = videoModelField,
                                        videoPromptField = videoPromptField,
                                        videoAuthHeader = videoAuthHeader,
                                        videoAuthPrefix = videoAuthPrefix,
                                        videoExtraRequestJson = videoExtraRequestJson,
                                        videoJobIdField = videoJobIdField,
                                        videoStatusField = videoStatusField,
                                        videoResultUrlField = videoResultUrlField
                                )
                                config.validate()
                                store.save(config)
                                store.activate(config.providerId)
                                config
                            }
                        }
                        result.onSuccess { config ->
                            MediaIntelligenceRegistry.register(OpenAiCompatibleMediaIntelligenceProvider(config))
                            MediaIntelligenceRegistry.activate(config.providerId)
                            configured = true
                            providerId = config.providerId
                            apiKey = ""
                            providerSummaries = withContext(Dispatchers.IO) { store.list() }
                            status = "媒体服务已保存并启用。密钥不会显示或写入普通日志。"
                        }.onFailure { status = "保存失败："+(it.message ?: "配置无效") }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "处理中…" else "保存并启用媒体服务") }
            if (providerSummaries.isNotEmpty()) {
                Text("已配置服务商", style = MaterialTheme.typography.titleSmall)
                providerSummaries.forEach { summary ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(summary.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                summary.providerId + " · 聊天：" + summary.model.ifBlank { "未配置" } +
                                    (summary.imageGenerationModel.takeIf { it.isNotBlank() }?.let { " · 生图：$it" } ?: "") +
                                    (summary.videoGenerationModel.takeIf { it.isNotBlank() }?.let { " · 视频：$it" } ?: " · 未配置视频模型"), 
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        store.activate(summary.providerId)
                                        store.read(summary.providerId) ?: error("服务商密钥不可用")
                                    }
                                }
                                result.onSuccess { config ->
                                    MediaIntelligenceRegistry.register(OpenAiCompatibleMediaIntelligenceProvider(config))
                                    MediaIntelligenceRegistry.activate(config.providerId)
                                    providerId = config.providerId
                                    displayName = config.displayName
                                    baseUrl = config.baseUrl
                                    model = config.model
                                    transcriptionModel = config.transcriptionModel
                                    transcriptionPath = config.transcriptionPath
                                    scenePath = config.scenePath
                                    audioPath = config.audioProcessPath
                                    imageModel = config.imageGenerationModel
                                    imagePath = config.imageGenerationPath
                                    videoModel = config.videoGenerationModel
                                    videoSubmitPath = config.videoSubmitPath
                                    videoStatusPath = config.videoStatusPath
                                    videoModelField = config.videoModelField
                                    videoPromptField = config.videoPromptField
                                    videoAuthHeader = config.videoAuthHeader
                                    videoAuthPrefix = config.videoAuthPrefix
                                    videoExtraRequestJson = config.videoExtraRequestJson
                                    videoJobIdField = config.videoJobIdField
                                    videoStatusField = config.videoStatusField
                                    videoResultUrlField = config.videoResultUrlField
                                    apiKey = ""
                                    configured = true
                                    status = "已切换到 "+config.displayName
                                }.onFailure { status = "切换失败："+(it.message ?: "配置不可用") }
                                busy = false
                            }
                        }) { Text("切换") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        store.delete(summary.providerId)
                                        store.read()
                                    }
                                }
                                result.onSuccess { fallback ->
                                    MediaIntelligenceRegistry.unregister(summary.providerId)
                                    if (fallback != null) {
                                        MediaIntelligenceRegistry.register(OpenAiCompatibleMediaIntelligenceProvider(fallback))
                                        MediaIntelligenceRegistry.activate(fallback.providerId)
                                        providerId = fallback.providerId
                                        displayName = fallback.displayName
                                        baseUrl = fallback.baseUrl
                                        model = fallback.model
                                        transcriptionPath = fallback.transcriptionPath
                                        scenePath = fallback.scenePath
                                        audioPath = fallback.audioProcessPath
                                        transcriptionModel = fallback.transcriptionModel
                                        videoModel = fallback.videoGenerationModel
                                        videoSubmitPath = fallback.videoSubmitPath
                                        videoStatusPath = fallback.videoStatusPath
                                        videoModelField = fallback.videoModelField
                                        videoPromptField = fallback.videoPromptField
                                        videoAuthHeader = fallback.videoAuthHeader
                                        videoAuthPrefix = fallback.videoAuthPrefix
                                        videoExtraRequestJson = fallback.videoExtraRequestJson
                                        videoJobIdField = fallback.videoJobIdField
                                        videoStatusField = fallback.videoStatusField
                                        videoResultUrlField = fallback.videoResultUrlField
                                        apiKey = ""
                                        configured = true
                                    } else configured = false
                                    providerSummaries = withContext(Dispatchers.IO) { store.list() }
                                    status = "已删除服务商配置："+summary.displayName
                                }.onFailure { status = "删除失败："+(it.message ?: "未知错误") }
                                busy = false
                            }
                        }) { Text("删除") }
                    }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
