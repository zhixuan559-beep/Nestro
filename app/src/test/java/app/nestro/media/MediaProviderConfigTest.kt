package app.nestro.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProviderConfigTest {
    private fun config(
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String = "secret-key"
    ) = MediaProviderConfig(
        displayName = "测试媒体服务",
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = "vision-model"
    )

    @Test fun acceptsHttpsProviderConfiguration() {
        config().validate()
    }

    @Test fun rejectsHttpAndEmbeddedCredentials() {
        assertFalse(runCatching { config("http://api.example.com").validate() }.isSuccess)
        assertFalse(runCatching { config("https://user:pass@api.example.com").validate() }.isSuccess)
    }

    @Test fun rejectsQueryFragmentAndPathTraversal() {
        assertFalse(runCatching { config("https://api.example.com/v1?token=x").validate() }.isSuccess)
        assertFalse(runCatching { config("https://api.example.com/v1#fragment").validate() }.isSuccess)
        assertFalse(runCatching { config("https://api.example.com/v1/../admin").validate() }.isSuccess)
    }

    @Test fun rejectsUnsafeApiPathsAndControlCharacters() {
        assertFalse(runCatching { config().copy(scenePath="../admin").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(apiKey="secret\nkey").validate() }.isSuccess)
    }

    @Test fun secretIsRedactedFromStringRepresentation() {
        val rendered = config().toString()
        assertTrue(rendered.contains("[REDACTED]"))
        assertFalse(rendered.contains("secret-key"))
    }
}
