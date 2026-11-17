package app.nestro.data

import app.nestro.model.ChatExecutionService
import java.util.UUID

internal fun isCreationIntent(prompt: String, target: String): Boolean {
    val text = prompt.trim()
    val hasCreationVerb = listOf("创建", "新建", "设计", "生成", "做一个", "帮我做").any { text.contains(it) }
    val hasNegation = listOf("不要", "不需要", "取消", "别创建").any { text.contains(it) }
    return text.contains(target) && hasCreationVerb && !hasNegation
}

internal fun parseGeneratedDefinition(raw: String): Pair<String, String> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型没有返回有效 JSON" }
    val json = org.json.JSONObject(raw.substring(start, end + 1))
    val name = json.optString("name").trim()
    val prompt = json.optString("prompt").trim()
    require(name.isNotBlank() && name.length <= 40) { "能力名称为空或过长" }
    require(prompt.length in 80..8000) { "能力指令长度不符合要求" }
    return name to prompt
}

internal fun interface CapabilityDraftTransport {
    fun complete(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        onUsage: (ModelConfig, Int, Int) -> Unit
    ): String
}

internal class CapabilityDraftCoordinator(
    private val transport: CapabilityDraftTransport
) {
    fun generate(
        kind: String,
        prompt: String,
        existingNames: String,
        modelConfigs: List<ModelConfig>,
        onUsage: (ModelConfig, Int, Int) -> Unit
    ): Pair<String, String> {
        require(kind == "expert" || kind == "skill") { "未知能力类型" }
        val generatorPrompt = buildString {
            append("你是 Nestro 的能力设计器。根据用户需求设计一个可直接使用的")
            append(if (kind == "expert") "AI 专家" else "工作技能")
            append("。只返回一个 JSON 对象，不要 Markdown 代码围栏或解释。字段必须是 name 和 prompt。name 是简短中文名称，prompt 是完整、具体、可执行的角色指令。不要要求扩大手机权限，不要包含可执行代码，不要虚构外部工具。现有名称：")
            append(existingNames.ifBlank { "无" })
            append("。名称必须与现有名称不同。")
        }
        val raw = transport.complete(
            modelConfigs,
            listOf(ChatMessage(UUID.randomUUID().toString(), "user", prompt)),
            generatorPrompt,
            onUsage
        )
        return parseGeneratedDefinition(raw)
    }
}
