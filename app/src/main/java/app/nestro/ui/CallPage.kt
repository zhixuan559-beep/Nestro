package app.nestro.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nestro.R
import app.nestro.speech.OpenAiRealtimeVoiceProvider
import app.nestro.speech.RealtimeVoiceAudioController
import app.nestro.speech.RealtimeVoiceListener
import app.nestro.speech.RealtimeVoiceSession
import app.nestro.speech.SpeechAudio
import app.nestro.speech.SpeechProviderConfigStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** 通话时长 mm:ss（纯函数，便于单测）。 */
internal fun formatCallDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(Locale.ROOT, safe / 60, safe % 60)
}

private enum class CallConnectionStatus {
    NOT_CONFIGURED, CONNECTING, CONNECTED, DISCONNECTED, ERROR
}

/**
 * 通话页（改版第 e 步，设计图 09/10/11）：
 * 聊天顶栏电话键进入；头像是卡皮巴拉本尊（软框）；三键——音频（静音/免提合一：点按静音，通话默认免提）、挂断、视频；
 * 视频界面去框——虚拟形象直接落画面里不加卡片框，用户小窗浮在右上，点小窗互换大小。
 * 语音通话走既有实时语音通道（OpenAiRealtimeVoiceProvider + RealtimeVoiceAudioController），
 * 未配置语音服务时只显示界面并引导去配置，不编造已接通的假状态。
 */
@Composable
fun CallPage(
    calleeName: String,
    onHangup: () -> Unit,
    onOpenVoiceSettings: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val providerStore = remember(context) { SpeechProviderConfigStore(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val sessionRef = remember { AtomicReference<RealtimeVoiceSession?>(null) }
    val pendingSessionRef = remember { AtomicReference<RealtimeVoiceSession?>(null) }
    val audioRef = remember { AtomicReference<RealtimeVoiceAudioController?>(null) }
    var callSeconds by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var videoEnabled by remember { mutableStateOf(false) }
    var swapped by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf(CallConnectionStatus.NOT_CONFIGURED) }
    var connectionError by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }

    fun connectRealtime() {
        val config = providerStore.read()
        if (config == null) {
            connectionStatus = CallConnectionStatus.NOT_CONFIGURED
            return
        }
        connectionStatus = CallConnectionStatus.CONNECTING
        try {
            val session = OpenAiRealtimeVoiceProvider().connect(config, object : RealtimeVoiceListener {
                override fun onConnected(activeSession: RealtimeVoiceSession) {
                    try {
                        val audio = RealtimeVoiceAudioController(context, activeSession) { message ->
                            activeSession.close()
                            mainHandler.post {
                                connectionError = message
                                connectionStatus = CallConnectionStatus.ERROR
                            }
                        }
                        audio.start()
                        audio.setMicrophoneMuted(muted)
                        audioRef.set(audio)
                        sessionRef.set(activeSession)
                        mainHandler.post { connectionStatus = CallConnectionStatus.CONNECTED }
                    } catch (error: Throwable) {
                        activeSession.close()
                        mainHandler.post {
                            connectionError = error.message ?: "实时音频设备启动失败"
                            connectionStatus = CallConnectionStatus.ERROR
                        }
                    }
                }

                override fun onTranscript(text: String, isFinal: Boolean) {
                    mainHandler.post {
                        if (isFinal) transcript = (transcript + text + "\n").takeLast(500)
                    }
                }

                override fun onAudio(audio: SpeechAudio) {
                    runCatching { audioRef.get()?.play(audio) }.onFailure {
                        sessionRef.get()?.close()
                        mainHandler.post {
                            connectionError = "实时音频播放失败"
                            connectionStatus = CallConnectionStatus.ERROR
                        }
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        connectionError = message
                        connectionStatus = CallConnectionStatus.ERROR
                    }
                }

                override fun onClosed() {
                    audioRef.getAndSet(null)?.close()
                    mainHandler.post {
                        if (connectionStatus == CallConnectionStatus.CONNECTED ||
                            connectionStatus == CallConnectionStatus.CONNECTING
                        ) {
                            connectionStatus = CallConnectionStatus.DISCONNECTED
                        }
                    }
                }
            })
            pendingSessionRef.set(session)
        } catch (error: Throwable) {
            connectionError = error.message ?: "实时语音连接失败"
            connectionStatus = CallConnectionStatus.ERROR
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            connectRealtime()
        } else {
            connectionError = "需要麦克风权限才能通话"
            connectionStatus = CallConnectionStatus.ERROR
        }
    }

    LaunchedEffect("call-connect") {
        if (providerStore.read() == null) {
            connectionStatus = CallConnectionStatus.NOT_CONFIGURED
        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            connectRealtime()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect("call-timer") {
        while (true) {
            delay(1_000)
            callSeconds++
        }
    }
    LaunchedEffect(muted) {
        audioRef.get()?.setMicrophoneMuted(muted)
    }
    DisposableEffect(Unit) {
        val previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        onDispose {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = previousMode
            audioRef.getAndSet(null)?.close()
            sessionRef.getAndSet(null)?.close()
            pendingSessionRef.getAndSet(null)?.close()
        }
    }

    fun hangup() {
        audioRef.getAndSet(null)?.close()
        sessionRef.getAndSet(null)?.close()
        pendingSessionRef.getAndSet(null)?.close()
        onHangup()
    }

    val audioStateText = if (muted) "已静音" else "免提中"
    val durationText = formatCallDuration(callSeconds)
    val subtitle = when (connectionStatus) {
        CallConnectionStatus.NOT_CONFIGURED -> "正在通话 · $durationText · $audioStateText"
        CallConnectionStatus.CONNECTING -> "正在连接语音服务… · $durationText"
        CallConnectionStatus.CONNECTED -> "正在通话 · $durationText · $audioStateText"
        CallConnectionStatus.DISCONNECTED -> "语音连接已断开 · $durationText"
        CallConnectionStatus.ERROR -> connectionError.ifBlank { "语音连接失败" }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (videoEnabled) {
            // 视频模式（设计图 10/11）：去框大窗 + 右上小窗，点小窗互换大小。
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (!swapped) {
                    Image(
                        painter = painterResource(R.drawable.mascot_capybara),
                        contentDescription = "虚拟形象",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x8A000000)
                    ) {
                        Text(
                            "虚拟头像",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFFECECEE)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("你的画面（已放大）", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // 用户小窗（右上），点按互换大小；自拍画面暂为占位（与设计图一致），摄像头接入另起一步。
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)
                        .size(92.dp, 128.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE9E9EA))
                        .border(2.dp, Color.White, RoundedCornerShape(16.dp))
                        .clickable { swapped = !swapped }
                        .semantics { contentDescription = "切换大小" },
                    contentAlignment = Alignment.Center
                ) {
                    if (!swapped) {
                        Text("你的画面", color = Muted, style = MaterialTheme.typography.labelSmall)
                    } else {
                        Image(
                            painter = painterResource(R.drawable.mascot_capybara),
                            contentDescription = "虚拟形象",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x8A000000)
                        ) {
                            Text(
                                "点我互换",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // 语音模式（设计图 09）：卡皮巴拉本尊住软框。
            Spacer(Modifier.height(66.dp))
            Box(Modifier.shadow(18.dp, RoundedCornerShape(48.dp))) {
                MascotAvatar(mascot = "capybara", avatar = "✦", name = calleeName, size = 150.dp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            calleeName,
            color = Ink,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(7.dp))
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(22.dp))
        CallWaveform(active = connectionStatus == CallConnectionStatus.CONNECTED && !muted)
        if (transcript.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                transcript.trim().takeLast(200),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 3
            )
        }
        if (connectionStatus == CallConnectionStatus.NOT_CONFIGURED) {
            Spacer(Modifier.height(16.dp))
            Text(
                "未配置语音服务：先去配置，对方才能实时听到你",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenVoiceSettings) { Text("去配置语音服务") }
        }
        if (connectionStatus == CallConnectionStatus.ERROR ||
            connectionStatus == CallConnectionStatus.DISCONNECTED
        ) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { connectRealtime() }) { Text("重新连接") }
        }
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(bottom = 60.dp)
        ) {
            CallButton(
                icon = if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                label = "音频 · $audioStateText",
                contentDescription = "音频",
                onClick = { muted = !muted }
            )
            CallButton(
                icon = Icons.Outlined.CallEnd,
                label = "挂断",
                contentDescription = "挂断",
                red = true,
                onClick = { hangup() }
            )
            CallButton(
                icon = if (videoEnabled) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam,
                label = if (videoEnabled) "关视频" else "视频",
                contentDescription = "视频",
                onClick = {
                    videoEnabled = !videoEnabled
                    swapped = false
                }
            )
        }
    }
}

/** 通话波形条：接通且未静音时起伏，未接通时静止。 */
@Composable
private fun CallWaveform(active: Boolean) {
    val barHeights = listOf(10, 18, 26, 14, 22, 28, 16, 24, 12, 20, 27, 15, 21, 11, 19, 25, 13)
    val transition = rememberInfiniteTransition(label = "call-wave")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "call-wave-progress"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(30.dp)
    ) {
        barHeights.forEachIndexed { index, base ->
            val factor = if (active) {
                val phase = (progress.toDouble() + index * 0.09) * 2.0 * PI
                0.55f + 0.45f * sin(phase).toFloat()
            } else {
                1f
            }
            Box(
                Modifier.width(4.dp).height((base * factor).dp)
                    .background(Ink, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun CallButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    red: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(64.dp).semantics { this.contentDescription = contentDescription },
            shape = CircleShape,
            color = if (red) Color(0xFFE5484D) else Color(0xFF262626)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}
