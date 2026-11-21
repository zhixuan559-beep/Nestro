package app.nestro.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Owns Android's on-device text-to-speech engine.
 *
 * No audio is uploaded by this controller. Availability and installed voices are
 * supplied by the Android TTS engines configured on the device.
 */
class AndroidTextToSpeechController(
    context: Context,
    private val listener: Listener
) : AutoCloseable {
    interface Listener {
        fun onReady(availableLanguages: Set<Locale>)
        fun onStarted(utteranceId: String)
        fun onCompleted(utteranceId: String)
        fun onError(utteranceId: String, message: String)
    }

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var closed = false
    private var currentUtteranceId: String? = null

    init {
        engine = TextToSpeech(appContext) { status ->
            if (closed) return@TextToSpeech
            val tts = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                listener.onError("", "系统语音合成引擎初始化失败")
                return@TextToSpeech
            }
            initialized = true
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    listener.onStarted(utteranceId)
                }

                override fun onDone(utteranceId: String) {
                    if (currentUtteranceId == utteranceId) currentUtteranceId = null
                    listener.onCompleted(utteranceId)
                }

                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String) {
                    if (currentUtteranceId == utteranceId) currentUtteranceId = null
                    listener.onError(utteranceId, "语音合成失败")
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    if (currentUtteranceId == utteranceId) currentUtteranceId = null
                    listener.onError(utteranceId, "语音合成失败（$errorCode）")
                }
            })
            listener.onReady(
                tts.availableLanguages.orEmpty().filter { it != Locale.ROOT }.toSet()
            )
        }
    }

    fun speak(
        text: String,
        locale: Locale = Locale.getDefault(),
        speechRate: Float = 1f,
        pitch: Float = 1f
    ): Boolean {
        if (closed || !initialized) {
            listener.onError("", "语音合成引擎尚未就绪")
            return false
        }
        val normalized = SpeechTextPolicy.normalize(text)
        if (normalized == null) {
            listener.onError("", "请输入需要朗读的文字（最多 4000 字符）")
            return false
        }
        if (speechRate !in 0.5f..2f || pitch !in 0.5f..2f) {
            listener.onError("", "语速和音调必须在 0.5 至 2.0 之间")
            return false
        }
        val tts = engine ?: return false
        val languageResult = tts.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            listener.onError("", "当前设备未安装该语言的语音数据")
            return false
        }
        tts.setSpeechRate(speechRate)
        tts.setPitch(pitch)
        tts.stop()
        val id = UUID.randomUUID().toString()
        currentUtteranceId = id
        val result = tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            currentUtteranceId = null
            listener.onError(id, "系统拒绝了本次语音合成请求")
            return false
        }
        return true
    }

    fun stop() {
        currentUtteranceId = null
        runCatching { engine?.stop() }
    }

    override fun close() {
        if (closed) return
        closed = true
        currentUtteranceId = null
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        initialized = false
    }
}

/** Shared input validation for local and remote speech output. */
object SpeechTextPolicy {
    const val MAX_CHARACTERS = 4_000

    fun normalize(text: String): String? {
        val normalized = text
            .replace("\u0000", "")
            .replace(Regex("[\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .trim()
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_CHARACTERS }
    }
}
