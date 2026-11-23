package app.nestro.data

import app.nestro.model.ModelModality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelCapabilityPolicyTest {
    @Test
    fun mediaAttachmentsRequireEveryDeclaredInputModality() {
        val history = listOf(
            ChatMessage(
                "message",
                "user",
                "分析这段素材",
                mediaParts = listOf(
                    ChatMessageMediaPart("image", "image/png", "a", 100),
                    ChatMessageMediaPart("audio", "audio/wav", "b", 200)
                )
            )
        )

        val required = ModelCapabilityPolicy.requiredInputModalities(history)
        assertEquals(setOf(ModelModality.IMAGE, ModelModality.AUDIO), required)

        val imageOnly = ModelConfig(
            "https://one.example.com", "image-only", "secret",
            inputModalities = setOf(ModelModality.TEXT, ModelModality.IMAGE)
        )
        val imageAndAudio = ModelConfig(
            "https://two.example.com", "multimodal", "secret",
            inputModalities = setOf(ModelModality.TEXT, ModelModality.IMAGE, ModelModality.AUDIO)
        )
        assertEquals(listOf(imageAndAudio), ModelCapabilityPolicy.compatibleConfigs(listOf(imageOnly, imageAndAudio), history))
    }

    @Test
    fun protocolLimitsExcludeUnsupportedMediaEvenWhenModelDeclaresIt() {
        val history = listOf(
            ChatMessage(
                "message",
                "user",
                "分析视频",
                mediaParts = listOf(ChatMessageMediaPart("VIDEO", "video/mp4", "data", 4_096))
            )
        )
        val openAi = ModelConfig(
            "https://openai.example.com", "vision-model", "secret",
            inputModalities = setOf(ModelModality.TEXT, ModelModality.IMAGE, ModelModality.VIDEO)
        )
        val gemini = ModelConfig(
            "https://gemini.example.com", "video-model", "secret",
            protocol = app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT,
            inputModalities = setOf(ModelModality.TEXT, ModelModality.IMAGE, ModelModality.VIDEO)
        )
        assertEquals(listOf(gemini), ModelCapabilityPolicy.compatibleConfigs(listOf(openAi, gemini), history))
    }

    @Test
    fun textOnlyRequestsKeepAllFailoverCandidates() {
        val configs = listOf(
            ModelConfig("https://one.example.com", "one", "secret"),
            ModelConfig("https://two.example.com", "two", "secret")
        )
        assertEquals(configs, ModelCapabilityPolicy.compatibleConfigs(configs, emptyList()))
    }

    @Test
    fun unknownMediaKindIsRejectedInsteadOfBeingSilentlySent() {
        val history = listOf(
            ChatMessage(
                "message",
                "user",
                "附件",
                mediaParts = listOf(ChatMessageMediaPart("archive", "application/zip", "x"))
            )
        )
        assertThrows(IllegalStateException::class.java) {
            ModelCapabilityPolicy.requiredInputModalities(history)
        }
    }
}
