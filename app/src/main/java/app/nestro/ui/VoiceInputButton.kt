package app.nestro.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@Composable
fun VoiceInputButton(enabled: Boolean, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val latestResult = rememberUpdatedState(onResult)
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val scale by animateFloatAsState(if (listening) 1.12f else 1f, label = "voice-button-scale")
    val buttonColor by animateColorAsState(if (listening) Color(0xFFB45353) else Color(0xFF202A27), label = "voice-button-color")
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* First press requests permission; press again to speak. */ }

    fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        listening = true
        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                engine.destroy()
                if (recognizer === engine) recognizer = null
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (phrase.isNotBlank()) latestResult.value(phrase)
                engine.destroy()
                if (recognizer === engine) recognizer = null
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        engine.startListening(intent)
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
        }
    }

    val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    Box(
        modifier = Modifier.size(52.dp).scale(scale)
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
                    tryAwaitRelease()
                    recognizer?.stopListening()
                    listening = false
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (listening) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            contentDescription = if (listening) "正在录音，松开结束" else "按住说话",
            tint = if (listening) Color.White else Color(0xFF73E2B4)
        )
    }
}
