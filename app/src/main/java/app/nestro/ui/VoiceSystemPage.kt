package app.nestro.ui

import android.content.Context
import app.nestro.speech.AndroidTextToSpeechController
import app.nestro.speech.OpenAiCompatibleSpeechOutputProvider
import app.nestro.speech.OpenAiCompatibleSpeechInputProvider
import app.nestro.speech.SpeechRecordingController
import app.nestro.speech.SpeechProviderConfig
import app.nestro.speech.SpeechProviderConfigStore
import app.nestro.speech.OpenAiRealtimeVoiceProvider
import app.nestro.speech.RealtimeVoiceAudioController
import app.nestro.speech.RealtimeVoiceListener
import app.nestro.speech.RealtimeVoiceSession
import app.nestro.speech.SpeechAudio
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.media.MediaPlayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
    val providerStore = remember(context) { SpeechProviderConfigStore(context) }
    val savedProvider = remember(context) { providerStore.read()?.copy(apiKey = "") }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val realtimeAudioRef = remember { AtomicReference<RealtimeVoiceAudioController?>(null) }
    var providerBaseUrl by remember { mutableStateOf(savedProvider?.baseUrl ?: "https://api.openai.com/v1") }
    var providerModel by remember { mutableStateOf(savedProvider?.model.orEmpty()) }
    var providerInputModel by remember { mutableStateOf(savedProvider?.inputModel ?: "whisper-1") }
    var providerOutputModel by remember { mutableStateOf(savedProvider?.outputModel ?: "gpt-4o-mini-tts") }
    var providerVoice by remember { mutableStateOf(savedProvider?.voice ?: "alloy") }
    var providerApiKey by remember { mutableStateOf("") }
    var providerConfigured by remember { mutableStateOf(savedProvider != null) }
    var remoteBusy by remember { mutableStateOf(false) }
    var remoteRecording by remember { mutableStateOf(false) }
    var speechRecorder by remember { mutableStateOf<SpeechRecordingController?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var realtimeSession by remember { mutableStateOf<RealtimeVoiceSession?>(null) }
    var pendingRealtimeSession by remember { mutableStateOf<RealtimeVoiceSession?>(null) }
    var realtimeConnecting by remember { mutableStateOf(false) }
    var realtimeAudio by remember { mutableStateOf<RealtimeVoiceAudioController?>(null) }
    var realtimeTranscript by remember { mutableStateOf("") }
    val voicePrefs = remember(context) {
        context.getSharedPreferences("nestro_voice_settings", Context.MODE_PRIVATE)
    }
    var speechText by remember { mutableStateOf("你好，欢迎使用 Nestro 语音系统。") }
    var speechRate by remember { mutableStateOf(voicePrefs.getFloat("speech_rate", 1f).coerceIn(0.5f, 2f)) }
    var pitch by remember { mutableStateOf(voicePrefs.getFloat("speech_pitch", 1f).coerceIn(0.5f, 2f)) }
    var selectedLanguage by remember {
        mutableStateOf(Locale.forLanguageTag(voicePrefs.getString("speech_language", "zh-CN") ?: "zh-CN"))
    }
    LaunchedEffect(speechRate, pitch, selectedLanguage) {
        voicePrefs.edit()
            .putFloat("speech_rate", speechRate)
            .putFloat("speech_pitch", pitch)
            .putString("speech_language", selectedLanguage.toLanguageTag())
            .apply()
    }
    var detectedLanguageCount by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在初始化本机语音合成…") }

    val controller = remember(context) {
        AndroidTextToSpeechController(context, object : AndroidTextToSpeechController.Listener {
            override fun onReady(availableLanguages: Set<Locale>) {
                ready = true
                detectedLanguageCount = availableLanguages.size
                status = if (availableLanguages.isEmpty()) {
                    "语音引擎已就绪；语言列表由系统引擎提供。"
                } else {
                    "语音引擎已就绪，检测到 ${detectedLanguageCount} 种语言。"
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
    fun startRemoteRecording() {
        try {
            controller.stop()
            val recorder = SpeechRecordingController()
            recorder.start()
            speechRecorder = recorder
            remoteRecording = true
            status = "正在录音，点击结束后会将音频发送到已配置的识别服务"
        } catch (error: Throwable) {
            remoteRecording = false
            status = error.message ?: "无法启动远程语音录音"
        }
    }

    fun stopRemoteRecording() {
        val recorder = speechRecorder ?: return
        speechRecorder = null
        remoteRecording = false
        remoteBusy = true
        status = "正在整理录音并请求远程识别…"
        scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) { recorder.stopAndGetWav() }
                val config = providerStore.read() ?: error("请先保存远程语音服务配置")
                val transcript = OpenAiCompatibleSpeechInputProvider().transcribe(wav, "audio/wav", config)
                speechText = transcript.text
                status = "远程语音识别完成，文本已回填"
            } catch (error: Throwable) {
                status = error.message ?: "远程语音识别失败"
            } finally {
                recorder.close()
                remoteBusy = false
            }
        }
    }

    fun connectRealtime() {
        val config = providerStore.read()
        if (config == null) {
            status = "请先保存远程语音服务配置"
            return
        }
        try {
            controller.stop()
            status = "正在连接实时语音服务…"
            realtimeConnecting = true
            val session = OpenAiRealtimeVoiceProvider().connect(config, object : RealtimeVoiceListener {
                override fun onConnected(activeSession: RealtimeVoiceSession) {
                    try {
                        val audio = RealtimeVoiceAudioController(activeSession) { message ->
                            activeSession.close()
                            mainHandler.post { status = message }
                        }
                        audio.start()
                        realtimeAudioRef.set(audio)
                        mainHandler.post {
                            realtimeAudio = audio
                            realtimeSession = activeSession
                            pendingRealtimeSession = null
                            realtimeConnecting = false
                            status = "实时双向语音已连接，可以直接说话"
                        }
                    } catch (error: Throwable) {
                        activeSession.close()
                        mainHandler.post { status = error.message ?: "实时音频设备启动失败" }
                    }
                }

                override fun onTranscript(text: String, isFinal: Boolean) {
                    mainHandler.post {
                        if (isFinal) realtimeTranscript = (realtimeTranscript + text + "\n").takeLast(2_000)
                        status = if (isFinal) "识别完成：$text" else "实时转写：$text"
                    }
                }

                override fun onAudio(audio: SpeechAudio) {
                    runCatching { realtimeAudioRef.get()?.play(audio) }
                        .onFailure { error -> mainHandler.post { status = error.message ?: "实时音频播放失败" } }
                }

                override fun onError(message: String) {
                    mainHandler.post { status = message }
                }

                override fun onClosed() {
                    realtimeAudioRef.getAndSet(null)?.close()
                    mainHandler.post {
                        realtimeAudio = null
                        realtimeSession = null
                        pendingRealtimeSession = null
                        realtimeConnecting = false
                        status = "实时语音连接已断开"
                    }
                }
            })
            pendingRealtimeSession = session
        } catch (error: Throwable) {
            realtimeConnecting = false
            status = error.message ?: "实时语音连接失败"
        }
    }

    val remoteRecordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRemoteRecording() else status = "需要麦克风权限才能使用远程语音识别"
    }

    val realtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) connectRealtime() else status = "需要麦克风权限才能使用实时语音"
    }

    DisposableEffect(controller) {
        onDispose {
            controller.close()
            speechRecorder?.close()
            realtimeAudioRef.getAndSet(null)?.close()
            realtimeSession?.close()
            pendingRealtimeSession?.close()
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            audioFile?.delete()
            audioFile = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("语音输入与输出")
        Text("语音输入：在聊天输入框按住麦克风说话，松开后由 Android 系统识别服务转写并发送。识别服务的数据处理方式取决于设备当前启用的系统语音服务。")
        Text("语音输出：调用设备已安装的 Android 语音合成引擎；是否联网及文本如何处理取决于该系统引擎，Nestro 不会将文本转发到自建语音服务。")
        OutlinedTextField(
            value = speechText,
            onValueChange = { speechText = it.take(4_000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("朗读内容（最多 4000 字符）") },
            minLines = 3
        )
        Text("文本长度：${speechText.length}/4000")
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
                enabled = ready && !remoteBusy && !remoteRecording && mediaPlayer == null && realtimeSession == null && pendingRealtimeSession == null && !realtimeConnecting,
                onClick = {
                    if (controller.speak(speechText, selectedLanguage, speechRate, pitch)) {
                        status = "已提交朗读任务…"
                    }
                }
            ) { Text("开始朗读") }
            OutlinedButton(
                enabled = ready || mediaPlayer != null,
                onClick = {
                    controller.stop()
                    runCatching { mediaPlayer?.stop() }
                    runCatching { mediaPlayer?.release() }
                    mediaPlayer = null
                    audioFile?.delete()
                    audioFile = null
                    status = "已停止朗读"
                }
            ) { Text("停止") }
        }
        Text("第三方语音服务（可选）")
        Text(
            "只有你主动启动远程录音、远程朗读或实时语音时，音频/文本才会发送到下方配置的 HTTPS 服务商。API Key 使用 Android Keystore 加密保存。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = providerBaseUrl,
            onValueChange = { providerBaseUrl = it.take(300) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务地址（HTTPS）") },
            singleLine = true
        )
        OutlinedTextField(
            value = providerModel,
            onValueChange = { providerModel = it.take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("实时语音模型") },
            singleLine = true
        )
        OutlinedTextField(
            value = providerInputModel,
            onValueChange = { providerInputModel = it.take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("语音识别模型") },
            singleLine = true
        )
        OutlinedTextField(
            value = providerOutputModel,
            onValueChange = { providerOutputModel = it.take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("语音合成模型") },
            singleLine = true
        )
        OutlinedTextField(
            value = providerVoice,
            onValueChange = { providerVoice = it.take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("音色 ID") },
            singleLine = true
        )
        OutlinedTextField(
            value = providerApiKey,
            onValueChange = { providerApiKey = it.take(4096) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (providerConfigured) "API Key（留空则保留已保存密钥）" else "API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Text("远程语音识别")
        OutlinedButton(
            enabled = providerConfigured && providerInputModel.isNotBlank() &&
                (!remoteBusy || remoteRecording) && realtimeSession == null && pendingRealtimeSession == null &&
                !realtimeConnecting && mediaPlayer == null,
            onClick = {
                if (remoteRecording) {
                    stopRemoteRecording()
                } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startRemoteRecording()
                } else {
                    remoteRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        ) { Text(if (remoteRecording) "结束录音并识别" else "开始远程录音") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !remoteBusy && !remoteRecording && realtimeSession == null && pendingRealtimeSession == null && !realtimeConnecting,
                onClick = {
                val existingKey = if (providerApiKey.isBlank()) providerStore.read()?.apiKey.orEmpty() else providerApiKey
                runCatching {
                    providerStore.save(
                        SpeechProviderConfig(
                            providerId = "openai-compatible",
                            baseUrl = providerBaseUrl,
                            apiKey = existingKey,
                            model = providerModel,
                            voice = providerVoice.ifBlank { null },
                            inputModel = providerInputModel,
                            outputModel = providerOutputModel
                        )
                    )
                }.onSuccess {
                    providerApiKey = ""
                    providerConfigured = true
                    status = "远程语音服务配置已加密保存"
                }.onFailure {
                    status = it.message ?: "远程语音服务配置无效"
                }
            }) { Text("保存配置") }
            OutlinedButton(
                enabled = providerConfigured && !remoteBusy && !remoteRecording &&
                    realtimeSession == null && pendingRealtimeSession == null && !realtimeConnecting && mediaPlayer == null,
                onClick = {
                    runCatching { providerStore.delete() }
                        .onSuccess {
                            providerConfigured = false
                            providerApiKey = ""
                            status = "远程语音服务配置已删除"
                        }
                        .onFailure { status = it.message ?: "删除远程语音服务配置失败" }
                }
            ) { Text("删除配置") }
        }
        OutlinedButton(
            enabled = providerConfigured && providerOutputModel.isNotBlank() && !remoteBusy && !remoteRecording &&
                mediaPlayer == null && realtimeSession == null && pendingRealtimeSession == null && !realtimeConnecting,
            onClick = {
                scope.launch {
                    remoteBusy = true
                    controller.stop()
                    status = "正在请求远程语音合成…"
                    try {
                        val config = providerStore.read() ?: error("请先保存远程语音服务配置")
                        val audio = OpenAiCompatibleSpeechOutputProvider().synthesize(speechText, config)
                        mediaPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
                        audioFile?.delete()
                        val file = File.createTempFile("nestro-voice-", ".audio", context.cacheDir)
                        audioFile = file
                        file.writeBytes(audio.bytes)
                        val player = MediaPlayer()
                        mediaPlayer = player
                        player.setDataSource(file.absolutePath)
                        player.setOnPreparedListener {
                            it.start()
                            status = "远程语音朗读中…"
                        }
                        player.setOnCompletionListener {
                            it.release()
                            file.delete()
                            mediaPlayer = null
                            audioFile = null
                            status = "远程语音朗读完成"
                        }
                        player.setOnErrorListener { mp, _, _ ->
                            mp.release()
                            file.delete()
                            mediaPlayer = null
                            audioFile = null
                            status = "远程音频播放失败"
                            true
                        }
                        player.prepareAsync()
                    } catch (error: Exception) {
                        runCatching { mediaPlayer?.release() }
                        mediaPlayer = null
                        audioFile?.delete()
                        audioFile = null
                        status = error.message ?: "远程语音合成失败"
                    } finally {
                        remoteBusy = false
                    }
                }
            }
        ) { Text(if (remoteBusy) "正在合成…" else "远程朗读预览") }
        Text("实时双向语音")
        Text(
            "通过加密保存的服务配置建立实时连接；音频将直接发送到你选择的服务商。麦克风权限仅在启动时请求。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = providerConfigured && providerModel.isNotBlank() && realtimeSession == null &&
                    pendingRealtimeSession == null && !realtimeConnecting && !remoteRecording && !remoteBusy && mediaPlayer == null,
                onClick = {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        connectRealtime()
                    } else {
                        realtimePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) { Text("开启实时语音") }
            OutlinedButton(
                enabled = realtimeSession != null || pendingRealtimeSession != null || realtimeConnecting,
                onClick = {
                    realtimeAudioRef.getAndSet(null)?.close()
                    realtimeAudio = null
                    realtimeSession?.close()
                    pendingRealtimeSession?.close()
                    realtimeSession = null
                    pendingRealtimeSession = null
                    realtimeConnecting = false
                    status = "已结束实时语音"
                }
            ) { Text("结束连接") }
        }
        OutlinedButton(
            enabled = realtimeSession != null && speechText.isNotBlank(),
            onClick = {
                runCatching { realtimeSession?.sendText(speechText) }
                    .onFailure { status = it.message ?: "文本发送失败" }
            }
        ) { Text("向实时语音发送文本") }
        if (realtimeTranscript.isNotBlank()) {
            Text("实时转写记录", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Text(realtimeTranscript, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Text(status)
    }
}
