package app.nestro.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

data class TtsVoiceOption(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val latency: Int
)

object TtsCapability {
    fun chineseVoices(engine: TextToSpeech): List<TtsVoiceOption> =
        engine.voices.orEmpty()
            .filter { it.locale.language.equals(Locale.CHINESE.language, true) }
            .distinctBy { it.name }
            .sortedWith(compareBy<Voice> { it.latency }.thenByDescending { it.quality })
            .map { TtsVoiceOption(it.name, it.locale.toLanguageTag(), it.quality, it.latency) }

    fun isLocaleAvailable(engine: TextToSpeech, locale: Locale): Boolean =
        engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE

    fun safeRate(rate: Float): Float = rate.coerceIn(0.7f, 1.3f)
}
