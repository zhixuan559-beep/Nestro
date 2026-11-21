package app.nestro.speech

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechProviderConfigStoreTest {
    @Test
    fun storesProviderMetadataAndEncryptsApiKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SpeechProviderConfigStore(context)
        runCatching { store.delete() }
        val secret = "speech-store-test-secret"
        val config = SpeechProviderConfig(
            providerId = "openai-compatible",
            baseUrl = "https://speech.example.com/v1",
            apiKey = secret,
            model = "realtime-model",
            voice = "alloy",
            inputModel = "transcription-model",
            outputModel = "synthesis-model"
        )

        store.save(config)

        assertEquals(config, store.read())
        val metadata = context.getSharedPreferences("nestro_voice_provider_v1", Context.MODE_PRIVATE)
        assertFalse(metadata.all.values.any { it.toString().contains(secret) })
        val firstKeyId = metadata.getString("provider_metadata", null)!!
            .let { org.json.JSONObject(it).getString("keyId") }
        val vault = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        assertFalse(vault.all.values.any { it.toString().contains(secret) })

        val updated = config.copy(
            apiKey = "speech-store-test-secret-rotated",
            inputModel = "new-transcription-model",
            outputModel = "new-synthesis-model"
        )
        store.save(updated)
        assertEquals(updated, store.read())
        val secondKeyId = org.json.JSONObject(metadata.getString("provider_metadata")!!).getString("keyId")
        assertFalse(firstKeyId == secondKeyId)
        assertFalse(vault.contains("model_api_key_v1_" + firstKeyId))
        assertFalse(vault.all.values.any { it.toString().contains(updated.apiKey) })

        store.delete()
        assertEquals(null, store.read())
    }
}
