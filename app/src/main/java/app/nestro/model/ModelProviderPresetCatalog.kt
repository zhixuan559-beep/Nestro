package app.nestro.model

/**
 * First-party provider presets. Presets remove endpoint/protocol guesswork;
 * users still own their credentials and provider billing relationship.
 */
enum class ModelApiProtocol {
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
    CUSTOM
}

data class ModelProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val protocol: ModelApiProtocol,
    val description: String,
    val credentialLabel: String = "API Key",
    val accountUrl: String? = null,
    val suggestedModels: List<String> = emptyList()
)

object ModelProviderPresetCatalog {
    val all: List<ModelProviderPreset> = listOf(
        ModelProviderPreset(
            "openai", "OpenAI", "https://api.openai.com/v1",
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS, "官方 OpenAI API",
            suggestedModels = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o")
        ),
        ModelProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com",
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS, "兼容 OpenAI；可使用 DeepSeek 官方模型",
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner")
        ),
        ModelProviderPreset(
            "anthropic", "Anthropic Claude", "https://api.anthropic.com",
            ModelApiProtocol.ANTHROPIC_MESSAGES, "原生 Claude Messages 协议",
            suggestedModels = listOf("claude-sonnet-4-5", "claude-haiku-4-5")
        ),
        ModelProviderPreset(
            "siliconflow", "硅基流动", "https://api.siliconflow.cn/v1",
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS, "模型广场与 API 服务",
            suggestedModels = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen3-8B")
        ),
        ModelProviderPreset(
            "modelscope", "魔搭社区", "https://api-inference.modelscope.cn/v1",
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS, "ModelScope API-Inference；额度以账户页面为准",
            credentialLabel = "魔搭访问令牌",
            accountUrl = "https://www.modelscope.cn/",
            suggestedModels = emptyList()
        ),
        ModelProviderPreset(
            "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta",
            ModelApiProtocol.GEMINI_GENERATE_CONTENT, "Google Gemini 原生 API",
            suggestedModels = listOf("gemini-2.5-flash", "gemini-2.5-pro")
        ),
        ModelProviderPreset(
            "dashscope", "阿里云百炼 / 通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS, "兼容 OpenAI 的百炼接口",
            suggestedModels = listOf("qwen-plus", "qwen-turbo", "qwen-max")
        )
    )

    fun byId(id: String): ModelProviderPreset? = all.firstOrNull { it.id == id }
}
