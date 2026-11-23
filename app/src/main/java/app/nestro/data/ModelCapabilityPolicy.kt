package app.nestro.data

import app.nestro.model.ModelApiProtocol
import app.nestro.model.ModelModality

/** Applies declared model input capabilities and transport limits before sending project media. */
internal object ModelCapabilityPolicy {
    fun requiredInputModalities(history: List<ChatMessage>): Set<ModelModality> =
        history.flatMap { it.mediaParts }.map { part ->
            when (part.kind.uppercase()) {
                "IMAGE" -> ModelModality.IMAGE
                "AUDIO" -> ModelModality.AUDIO
                "VIDEO" -> ModelModality.VIDEO
                else -> error("未知媒体类型，已阻止发送给模型：${part.kind}")
            }
        }.toSet()

    fun compatibleConfigs(
        configs: List<ModelConfig>,
        history: List<ChatMessage>
    ): List<ModelConfig> {
        val parts = history.flatMap { it.mediaParts }
        val requiredModalities = requiredInputModalities(history)
        if (requiredModalities.isEmpty()) return configs
        return configs.filter { config ->
            config.inputModalities.containsAll(requiredModalities) &&
                parts.all { part -> transportSupports(config.protocol, part) }
        }
    }

    private fun transportSupports(protocol: ModelApiProtocol, part: ChatMessageMediaPart): Boolean =
        when (protocol) {
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS -> when (part.kind.uppercase()) {
                "IMAGE" -> part.mimeType.startsWith("image/")
                "AUDIO" -> part.mimeType in setOf("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3")
                else -> false
            }
            ModelApiProtocol.ANTHROPIC_MESSAGES ->
                part.kind.equals("IMAGE", ignoreCase = true) && part.mimeType.startsWith("image/")
            ModelApiProtocol.GEMINI_GENERATE_CONTENT -> when (part.kind.uppercase()) {
                "IMAGE" -> part.mimeType.startsWith("image/")
                "AUDIO" -> part.mimeType.startsWith("audio/")
                "VIDEO" -> part.mimeType.startsWith("video/")
                else -> false
            }
            ModelApiProtocol.CUSTOM -> false
        }

    fun describe(modalities: Set<ModelModality>): String =
        modalities.map { modality ->
            when (modality) {
                ModelModality.TEXT -> "文本"
                ModelModality.IMAGE -> "图片"
                ModelModality.AUDIO -> "音频"
                ModelModality.VIDEO -> "视频"
            }
        }.joinToString("、")
}
