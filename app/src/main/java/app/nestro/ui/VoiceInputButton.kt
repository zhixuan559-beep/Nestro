package app.nestro.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

private enum class VoicePhase { IDLE, LISTENING, PROCESSING }

@Composable
fun VoiceInputButton(
    enabled: Boolean,
    onResult: (String) -> Unit,
    onPartialResult: (String) -> Unit = {},
    onStatus: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val latestResult = rememberUpdatedState(onResult)
    val latestPartial = rememberUpdatedState(onPartialResult)
    val latestStatus = rememberUpdatedState(onStatus)
    var phase by remember { mutableStateOf(VoicePhase.IDLE) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun releaseEngine(engine: SpeechRecognizer, cancel: Boolean = true) {
        if (cancel) runCatching { engine.cancel() }
        runCatching { engine.destroy() }
        if (recognizer === engine) recognizer = null
        phase = VoicePhase.IDLE
    }

    fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            latestStatus.value("当前设备没有可用的语音识别服务")
            return
        }
        if (recognizer != null) return
        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        phase = VoicePhase.LISTENING
        latestStatus.value("正在聆听，松开结束")
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { phase = VoicePhase.LISTENING }
            override fun onBeginningOfSpeech() { phase = VoicePhase.LISTENING }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                phase = VoicePhase.PROCESSING
                latestStatus.value("正在整理语音…")
            }
            override fun onError(error: Int) {
                latestStatus.value(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未授予"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到清晰语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试"
                        else -> "语音识别失败（$error）"
                    }
                )
                releaseEngine(engine, cancel = false)
            }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (phrase.isNotBlank()) {
                    latestPartial.value(phrase)
                    latestResult.value(phrase)
                    latestStatus.value("语音已转写并发送")
                } else {
                    latestStatus.value("没有识别到语音内容")
                }
                releaseEngine(engine, cancel = false)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (phrase.isNotBlank()) latestPartial.value(phrase)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { engine.startListening(intent) }.onFailure {
            latestStatus.value("无法启动语音识别：${it.message ?: "未知错误"}")
            releaseEngine(engine)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        latestStatus.value(if (granted) "麦克风权限已开启，再按住说话" else "需要麦克风权限才能使用语音输入")
    }

    val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val scale by animateFloatAsState(if (phase == VoicePhase.LISTENING) 1.12f else 1f, label = "voice-button-scale")
    val buttonColor by animateColorAsState(
        when (phase) {
            VoicePhase.LISTENING -> Color(0xFFB45353)
            VoicePhase.PROCESSING -> Color(0xFF66522B)
            VoicePhase.IDLE -> Color(0xFF202A27)
        },
        label = "voice-button-color"
    )

    DisposableEffect(Unit) {
        onDispose { recognizer?.let { releaseEngine(it) } }
    }

    Box(
        modifier = Modifier.size(52.dp).scale(scale)
            .semantics {
                contentDescription = when (phase) {
                    VoicePhase.LISTENING -> "正在录音，松开结束"
                    VoicePhase.PROCESSING -> "正在识别语音"
                    VoicePhase.IDLE -> "按住说话"
                }
                role = Role.Button
                if (!enabled) disabled()
            }
            .background(buttonColor, CircleShape)
            .pointerInput(enabled, hasPermission) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    startRecognition()
                    val released = tryAwaitRelease()
                    val engine = recognizer
                    if (engine != null) {
                        if (released) {
                            phase = VoicePhase.PROCESSING
                            latestStatus.value("正在识别语音…")
                            runCatching { engine.stopListening() }.onFailure { releaseEngine(engine) }
                        } else {
                            latestStatus.value("已取消语音输入")
                            releaseEngine(engine)
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (phase == VoicePhase.LISTENING) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            contentDescription = when (phase) {
                VoicePhase.LISTENING -> "正在录音，松开结束"
                VoicePhase.PROCESSING -> "正在识别语音"
                VoicePhase.IDLE -> "按住说话"
            },
            tint = if (phase == VoicePhase.IDLE) Color(0xFF73E2B4) else Color.White
        )
    }
}
