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

    @Test fun acceptsVideoOnlyProviderWithoutChatModel() {
        config().copy(model = "", transcriptionModel = "", videoGenerationModel = "video-model").validate()
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

    @Test fun acceptsConfigurableAsyncVideoGenerationContract() {
        config().copy(
            videoGenerationModel = "video-model",
            videoSubmitPath = "v1/videos",
            videoStatusPath = "v1/videos/{job_id}",
            videoModelField = "engine",
            videoPromptField = "input.prompt",
            videoJobIdField = "data.id",
            videoStatusField = "data.status",
            videoResultUrlField = "data.output.url"
        ).validate()
    }

    @Test fun rejectsUnsafeVideoGenerationRoutesAndFields() {
        assertFalse(runCatching { config().copy(videoSubmitPath = "../admin").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoStatusPath = "v1/videos").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoStatusPath = "v1/{job_id}/../admin").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoResultUrlField = "video-url?token").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoPromptField = "prompt.value?").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoAuthHeader = "Bad Header").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoAuthPrefix = "Bearer\n").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoExtraRequestJson = "[1,2]").validate() }.isSuccess)
        assertFalse(runCatching { config().copy(videoExtraRequestJson = "{broken").validate() }.isSuccess)
    }

    @Test fun secretIsRedactedFromStringRepresentation() {
        val rendered = config().toString()
        assertTrue(rendered.contains("[REDACTED]"))
        assertFalse(rendered.contains("secret-key"))
    }
}
