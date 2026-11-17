package app.nestro.ui

import android.Manifest
import android.content.pm.PackageManager
import app.nestro.speech.AndroidSpeechRecognitionController
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
    val controller = remember(context) { AndroidSpeechRecognitionController(context) }

    fun startRecognition() {
        phase = VoicePhase.LISTENING
        latestStatus.value("正在聆听，松开结束")
        controller.start(Locale.getDefault().toLanguageTag(), object : AndroidSpeechRecognitionController.Listener {
            override fun onListening() { phase = VoicePhase.LISTENING }
            override fun onProcessing() { phase = VoicePhase.PROCESSING }
            override fun onPartialResult(text: String) { latestPartial.value(text) }
            override fun onResult(text: String) { latestResult.value(text) }
            override fun onStatus(message: String) { latestStatus.value(message) }
        })
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
        onDispose { controller.close() }
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
                    if (released) {
                        phase = VoicePhase.PROCESSING
                        latestStatus.value("正在识别语音…")
                        controller.stopListening()
                    } else {
                        latestStatus.value("已取消语音输入")
                        controller.cancel()
                        phase = VoicePhase.IDLE
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (phase == VoicePhase.LISTENING) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            contentDescription = null,
            tint = if (phase == VoicePhase.IDLE) Color(0xFF73E2B4) else Color.White
        )
    }
}
