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
    val voice: String? = null
)

data class SpeechTranscript(
    val text: String,
    val isFinal: Boolean
)

data class SpeechAudio(
    val bytes: ByteArray,
    val mimeType: String
)

/** Optional speech-to-text provider contract for future user-configured services. */
interface SpeechInputProvider {
    val providerId: String

    suspend fun transcribe(
        audio: ByteArray,
        mimeType: String,
        config: SpeechProviderConfig
    ): SpeechTranscript
}

/** Optional text-to-speech provider contract; no provider is bundled by default. */
interface SpeechOutputProvider {
    val providerId: String

    suspend fun synthesize(
        text: String,
        config: SpeechProviderConfig
    ): SpeechAudio
}

/** Future full-duplex voice conversation provider contract. */
interface RealtimeVoiceProvider {
    val providerId: String

    fun connect(
        config: SpeechProviderConfig,
        listener: RealtimeVoiceListener
    ): RealtimeVoiceSession
}

interface RealtimeVoiceListener {
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
