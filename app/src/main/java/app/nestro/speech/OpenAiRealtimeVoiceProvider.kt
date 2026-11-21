package app.nestro.speech

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import android.util.Base64
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI Realtime-compatible WebSocket transport.
 *
 * Audio input must be signed 16-bit PCM in the provider's configured sample rate
 * (normally 24 kHz). Audio output is emitted as PCM chunks for the caller to play.
 */
class OpenAiRealtimeVoiceProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    override val providerId: String = "openai-compatible"
) : RealtimeVoiceProvider {

    override fun connect(
        config: SpeechProviderConfig,
        listener: RealtimeVoiceListener
    ): RealtimeVoiceSession {
        config.validateForRemoteProvider()
        require(config.providerId == providerId) { "语音服务商标识不匹配" }
        val request = Request.Builder()
            .url(realtimeUrl(config.baseUrl, config.model))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("OpenAI-Beta", "realtime=v1")
            .build()
        val closed = AtomicBoolean(false)
        lateinit var socket: WebSocket
        val session = object : RealtimeVoiceSession {
            override fun sendAudio(audio: ByteArray, mimeType: String) {
                check(!closed.get()) { "语音连接已关闭" }
                require(mimeType.startsWith("audio/pcm", ignoreCase = true)) {
                    "实时语音输入仅接受 PCM 音频，请先将录音转换为 PCM16"
                }
                require(audio.isNotEmpty() && audio.size <= MAX_AUDIO_CHUNK_BYTES && audio.size % 2 == 0) {
                    "音频分片为空或超过 1 MB"
                }
                val payload = JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", Base64.encodeToString(audio, Base64.NO_WRAP))
                check(socket.send(payload.toString())) { "实时语音音频发送失败" }
            }

            override fun sendText(text: String) {
                check(!closed.get()) { "语音连接已关闭" }
                val normalized = SpeechTextPolicy.normalize(text)
                    ?: throw IllegalArgumentException("发送文本为空或超过 4000 字符")
                val item = JSONObject()
                    .put("type", "conversation.item.create")
                    .put("item", JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", org.json.JSONArray().put(
                            JSONObject().put("type", "input_text").put("text", normalized)
                        )))
                check(socket.send(item.toString())) { "实时语音文本发送失败" }
                check(socket.send(JSONObject().put("type", "response.create").toString())) {
                    "实时语音响应请求发送失败"
                }
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    socket.close(1000, "client closed")
                }
            }
        }

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) {
                    webSocket.close(1000, "client closed")
                    return
                }
                val sessionUpdate = JSONObject()
                    .put("type", "session.update")
                    .put("session", JSONObject()
                        .put("modalities", org.json.JSONArray().put("text").put("audio"))
                        .put("voice", config.voice ?: "alloy")
                        .put("input_audio_format", "pcm16")
                        .put("output_audio_format", "pcm16")
                        .put("input_audio_transcription", JSONObject().put("model", "whisper-1"))
                        .put("turn_detection", JSONObject().put("type", "server_vad")))
                if (!webSocket.send(sessionUpdate.toString())) {
                    listener.onError("实时语音会话初始化消息发送失败")
                } else {
                    listener.onConnected(session)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                runCatching {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "conversation.item.input_audio_transcription.completed" ->
                            listener.onTranscript(event.optString("transcript"), true)
                        "response.audio_transcript.delta" ->
                            listener.onTranscript(event.optString("delta"), false)
                        "response.audio.delta" -> {
                            val encoded = event.optString("delta")
                            if (encoded.isNotBlank()) {
                                listener.onAudio(
                                    SpeechAudio(Base64.decode(encoded, Base64.DEFAULT), "audio/pcm;rate=24000")
                                )
                            }
                        }
                        "error" -> listener.onError(
                            event.optJSONObject("error")?.optString("message")?.take(500)
                                ?.ifBlank { "实时语音服务返回错误" } ?: "实时语音服务返回错误"
                        )
                    }
                }.onFailure { listener.onError("实时语音事件解析失败") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.get()) listener.onError("实时语音连接失败：${t.message?.take(180) ?: "网络错误"}")
                if (closed.compareAndSet(false, true)) listener.onClosed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed.compareAndSet(false, true)) listener.onClosed()
            }
        })
        return session
    }

    companion object {
        const val MAX_AUDIO_CHUNK_BYTES = 1 * 1024 * 1024

        internal fun realtimeUrl(baseUrl: String, model: String): String {
            val uri = URI(baseUrl.trim().trimEnd('/'))
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
            require(uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null)
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val encodedModel = java.net.URLEncoder.encode(model, Charsets.UTF_8.name())
            return "wss://${uri.rawAuthority}$path/realtime?model=$encodedModel"
        }
    }
}
