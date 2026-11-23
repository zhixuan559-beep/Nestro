package app.nestro.data

import app.nestro.model.ModelModality

/** Applies declared model input capabilities before any project media is sent to a provider. */
internal object ModelCapabilityPolicy {
    fun requiredInputModalities(history: List<ChatMessage>): Set<ModelModality> =
        history.flatMap { it.mediaParts }.map { part ->
            when (part.kind.lowercase()) {
                "image" -> ModelModality.IMAGE
                "audio" -> ModelModality.AUDIO
                "video" -> ModelModality.VIDEO
                else -> error("未知媒体类型，已阻止发送给模型：${part.kind}")
            }
        }.toSet()

    fun compatibleConfigs(
        configs: List<ModelConfig>,
        requiredModalities: Set<ModelModality>
    ): List<ModelConfig> = if (requiredModalities.isEmpty()) configs else {
        configs.filter { config -> config.inputModalities.containsAll(requiredModalities) }
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
