package app.nestro.speech

/**
 * Configuration passed to an explicitly selected speech provider.
 *
 * API keys are runtime secrets: callers must obtain them from encrypted local
 * storage and must never serialize this object into logs, backups, or source code.
 */
data class SpeechProviderConfig(
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val voice: String? = null,
    val inputModel: String = "whisper-1",
    val outputModel: String = "gpt-4o-mini-tts"
) {
    fun validateForRemoteProvider() {
        require(providerId.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) { "语音服务商标识无效" }
        require(baseUrl.length <= 2_048) { "语音服务地址过长" }
        val uri = java.net.URI(baseUrl)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "语音服务地址必须使用 HTTPS，且不能包含 URL 用户凭据"
        }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.none { it.code < 0x20 || it.code == 0x7f }) { "API Key 不能为空、不能包含控制字符且不能超过 4096 字符" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "语音服务地址不能包含查询参数或片段" }
        require(uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." }) { "语音服务地址不能包含路径回退片段" }
        require(model.isNotBlank() && model.length <= 120 && model.none { it.isISOControl() }) { "实时语音模型名称无效" }
        require(inputModel.isNotBlank() && inputModel.length <= 120 && inputModel.none { it.isISOControl() }) { "语音识别模型名称无效" }
        require(outputModel.isNotBlank() && outputModel.length <= 120 && outputModel.none { it.isISOControl() }) { "语音合成模型名称无效" }
        require(voice == null || (voice.length <= 120 && voice.none { it.isISOControl() })) { "音色标识无效或超出长度限制" }
    }

    override fun toString(): String = "SpeechProviderConfig(providerId=$providerId, baseUrl=$baseUrl, apiKey=[REDACTED], realtimeModel=$model, inputModel=$inputModel, outputModel=$outputModel, voice=$voice)"
}

data class SpeechTranscript(
    val text: String,
    val isFinal: Boolean
)

data class SpeechAudio(
    val bytes: ByteArray,
    val mimeType: String
)

/** Speech-to-text provider contract for user-configured remote services. */
interface SpeechInputProvider {
    val providerId: String

    suspend fun transcribe(
        audio: ByteArray,
        mimeType: String,
        config: SpeechProviderConfig
    ): SpeechTranscript
}

/** Text-to-speech provider contract; remote services are opt-in. */
interface SpeechOutputProvider {
    val providerId: String

    suspend fun synthesize(
        text: String,
        config: SpeechProviderConfig
    ): SpeechAudio
}

/** Full-duplex voice conversation provider contract. */
interface RealtimeVoiceProvider {
    val providerId: String

    fun connect(
        config: SpeechProviderConfig,
        listener: RealtimeVoiceListener
    ): RealtimeVoiceSession
}

interface RealtimeVoiceListener {
    fun onConnected(session: RealtimeVoiceSession) = Unit
    fun onTranscript(text: String, isFinal: Boolean)
    fun onAudio(audio: SpeechAudio)
    fun onError(message: String)
    fun onClosed()
}

interface RealtimeVoiceSession : AutoCloseable {
    fun sendAudio(audio: ByteArray, mimeType: String)
    fun sendText(text: String)

    override fun close()
}
