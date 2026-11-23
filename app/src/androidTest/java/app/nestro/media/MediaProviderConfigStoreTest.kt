package app.nestro.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class MediaProviderConfigStoreTest {
    private lateinit var store: MediaProviderConfigStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = MediaProviderConfigStore(context)
        runCatching { store.delete("media-test-a") }
        runCatching { store.delete("media-test-b") }
    }

    private fun config(id: String, key: String) = MediaProviderConfig(
        providerId = id,
        displayName = id,
        baseUrl = "https://api.example.com/v1",
        apiKey = key,
        model = "vision-model",
        transcriptionModel = "gpt-transcribe"
    )


    @Test fun onlyAdvertisesCapabilitiesWithConfiguredRealEndpoints() {
        val base = config("capability-test", "key")
        val defaultProvider = OpenAiCompatibleMediaIntelligenceProvider(base)
        assertEquals(setOf(MediaIntelligenceCapability.TRANSCRIPTION, MediaIntelligenceCapability.IMAGE_UNDERSTANDING), defaultProvider.capabilities)
        val extended = OpenAiCompatibleMediaIntelligenceProvider(base.copy(scenePath="v1/video/scenes", audioProcessPath="v1/audio/denoise"))
        assertEquals(true, MediaIntelligenceCapability.SCENE_DETECTION in extended.capabilities)
        assertEquals(true, MediaIntelligenceCapability.AUDIO_PROCESSING in extended.capabilities)
    }

    @Test fun storesSwitchesAndDeletesIndependentEncryptedProfiles() {
        try {
            store.save(config("media-test-a", "test-secret-a"))
            store.save(config("media-test-b", "test-secret-b"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val persisted = context.getSharedPreferences("nestro_media_provider_v1", Context.MODE_PRIVATE)
                .getString("provider_metadata", "{}").orEmpty()
            assertFalse(persisted.contains("test-secret-a") || persisted.contains("test-secret-b"))
            assertEquals("media-test-a", store.activeProviderId())
            store.activate("media-test-b")
            assertEquals("media-test-b", store.read()?.providerId)
            assertEquals("gpt-transcribe", store.read()?.transcriptionModel)
            assertNotNull(store.read("media-test-a"))
            assertEquals(2, store.list().size)
            store.delete("media-test-b")
            assertEquals("media-test-a", store.activeProviderId())
            assertEquals("media-test-a", store.read()?.providerId)
        } finally {
            runCatching { store.delete("media-test-a") }
            runCatching { store.delete("media-test-b") }
        }
    }
}
