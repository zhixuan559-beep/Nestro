package app.nestro.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechProvidersTest {
    @Test
    fun acceptsHttpsProviderConfiguration() {
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
        val insecure = SpeechProviderConfig(
            providerId = "provider",
            baseUrl = "http://speech.example.com",
            apiKey = "key",
            model = "model"
        )
        val embeddedCredentials = SpeechProviderConfig(
            providerId = "provider",
            baseUrl = "https://user:password@speech.example.com",
            apiKey = "key",
            model = "model"
        )

        assertThrows(IllegalArgumentException::class.java) {
            insecure.validateForRemoteProvider()
        }
        assertThrows(IllegalArgumentException::class.java) {
            embeddedCredentials.validateForRemoteProvider()
        }
    }

    @Test
    fun rejectsInvalidProviderAndModelFields() {
        val invalid = SpeechProviderConfig(
            providerId = "bad provider id",
            baseUrl = "https://speech.example.com",
            apiKey = "key",
            model = ""
        )

        assertThrows(IllegalArgumentException::class.java) {
            invalid.validateForRemoteProvider()
        }
    }
}
