package app.nestro.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Owns the Android speech-recognition engine independently of Compose presentation. */
class AndroidSpeechRecognitionController(context: Context) : AutoCloseable {
    interface Listener {
        fun onListening()
        fun onProcessing()
        fun onIdle()
        fun onPartialResult(text: String)
        fun onResult(text: String)
        fun onStatus(message: String)
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var activeListener: Listener? = null

    fun start(languageTag: String, listener: Listener) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onStatus("当前设备没有可用的语音识别服务")
            listener.onIdle()
            return
        }
        if (recognizer != null) return

        val engine = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (error: Throwable) {
            listener.onStatus("无法创建语音识别服务：${error.message ?: "未知错误"}")
            listener.onIdle()
            return
        }
        recognizer = engine
        activeListener = listener
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
            override fun onBeginningOfSpeech() = listener.onListening()
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listener.onProcessing()
                listener.onStatus("正在整理语音…")
            }
            override fun onError(error: Int) {
                listener.onStatus(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未授予"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到清晰语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试"
                        else -> "语音识别失败（$error）"
                    }
                )
                release(engine, cancel = false)
            }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (phrase.isNotBlank()) {
                    listener.onPartialResult(phrase)
                    listener.onResult(phrase)
                    listener.onStatus("语音已转写并发送")
                } else {
                    listener.onStatus("没有识别到语音内容")
                }
                release(engine, cancel = false)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (phrase.isNotBlank()) listener.onPartialResult(phrase)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { engine.startListening(intent) }.onFailure {
            listener.onStatus("无法启动语音识别：${it.message ?: "未知错误"}")
            release(engine)
        }
    }

    fun stopListening() {
        recognizer?.let { engine ->
            runCatching { engine.stopListening() }.onFailure { release(engine) }
        }
    }

    fun cancel() {
        recognizer?.let { engine -> release(engine) }
    }

    private fun release(engine: SpeechRecognizer, cancel: Boolean = true) {
        if (cancel) runCatching { engine.cancel() }
        runCatching { engine.destroy() }
        if (recognizer === engine) {
            recognizer = null
            activeListener?.onIdle()
            activeListener = null
        }
    }

    override fun close() {
        cancel()
    }
}
