package app.nestro.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechProvidersTest {
    @Test
    fun acceptsHttpsProviderConfigurationAndRedactsApiKey() {
        val config = SpeechProviderConfig(
            providerId = "custom-provider",
            baseUrl = "https://speech.example.com/v1",
            apiKey = "secret-test-key",
            model = "voice-model",
            voice = "voice-a"
        )

        config.validateForRemoteProvider()
        assertTrue(config.toString().contains("[REDACTED]"))
        assertFalse(config.toString().contains("secret-test-key"))
    }

    @Test
    fun rejectsInsecureOrCredentialEmbeddedEndpoints() {
        val insecure = SpeechProviderConfig("provider", "http://speech.example.com", "key", "model")
        val embeddedCredentials = SpeechProviderConfig(
            "provider", "https://user:password@speech.example.com", "key", "model"
        )
        val queryCredentials = SpeechProviderConfig(
            "provider", "https://speech.example.com/v1?token=secret", "key", "model"
        )

        assertThrows(IllegalArgumentException::class.java) { insecure.validateForRemoteProvider() }
        assertThrows(IllegalArgumentException::class.java) { embeddedCredentials.validateForRemoteProvider() }
        assertThrows(IllegalArgumentException::class.java) { queryCredentials.validateForRemoteProvider() }
    }

    @Test
    fun rejectsInvalidProviderModelAndEmptyKey() {
        val invalidProvider = SpeechProviderConfig(
            "bad provider id", "https://speech.example.com", "key", "model"
        )
        val invalidModel = SpeechProviderConfig(
            "provider", "https://speech.example.com", "key", ""
        )
        val emptyKey = SpeechProviderConfig(
            "provider", "https://speech.example.com", " ", "model"
        )
        val controlKey = SpeechProviderConfig(
            "provider", "https://speech.example.com", "key\\nInjected: true", "model"
        )
        val controlModel = SpeechProviderConfig(
            "provider", "https://speech.example.com", "key", "model\\nInjected"
        )

        assertThrows(IllegalArgumentException::class.java) { invalidProvider.validateForRemoteProvider() }
        assertThrows(IllegalArgumentException::class.java) { invalidModel.validateForRemoteProvider() }
        assertThrows(IllegalArgumentException::class.java) { emptyKey.validateForRemoteProvider() }
        assertThrows(IllegalArgumentException::class.java) { controlKey.validateForRemoteProvider() }
        assertThrows(IllegalArgumentException::class.java) { controlModel.validateForRemoteProvider() }
    }
}
