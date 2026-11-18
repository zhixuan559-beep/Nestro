package app.nestro.data

import app.nestro.model.ChatExecutionService
import org.json.JSONObject
import java.util.UUID

internal fun shouldExtractAutomaticMemory(prompt: String): Boolean {
    val signals = listOf("记住", "以后", "长期", "我喜欢", "我不喜欢", "我偏好", "我习惯", "我的工作", "我负责", "我正在做", "这个项目", "不要再", "我希望你", "我更倾向", "默认")
    return prompt.length in 8..4000 && signals.any { prompt.contains(it) }
}

internal fun extractMemoryCandidates(
    chatExecutionService: ChatExecutionService,
    modelConfigs: List<ModelConfig>,
    userMessages: List<String>,
    onUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
    onProviderUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> }
): List<MemoryCandidate> {
    val system = """
你是本地记忆候选提取器。只返回 JSON 对象：{"memories":[{"category":"preference","content":"...","confidence":0.9}]}。
只从用户消息中提取明确陈述、可长期复用的偏好、工作方式、项目事实或约束。
category 只能是 preference、workflow、project、constraint。
禁止推断；禁止提取助手说过但用户未确认的内容；不要把提问、一次性任务、假设当作长期事实。
绝不提取或复述健康、医疗、财务/债务、政治、宗教、种族、性取向、身份凭据、精确住址、电话号码、邮箱、证件或银行卡等敏感信息。
置信度低于 0.8 的内容不要返回。最多 5 条，每条 8 到 180 个字符。
没有合格内容时返回 {"memories":[]}。
""".trimIndent()
    val user = userMessages.takeLast(6).joinToString("\n").take(6000)
    val raw = chatExecutionService.completeWithFallback(
        modelConfigs,
        listOf(ChatMessage(UUID.randomUUID().toString(), "user", user)),
        system,
        onUsage = onUsage,
        onProviderUsage = onProviderUsage
    )
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    val rows = JSONObject(raw.substring(start, end + 1)).optJSONArray("memories") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { index ->
        val item = rows.optJSONObject(index) ?: return@mapNotNull null
        MemoryCandidate(
            category = item.optString("category"),
            content = item.optString("content"),
            confidence = item.optDouble("confidence", 0.0)
        )
    }.take(5)
}

internal class AutomaticMemoryExtractionCoordinator(
    private val extractCandidates: (List<ModelConfig>, List<String>) -> List<MemoryCandidate>,
    private val saveCandidates: suspend (String, String, String, List<MemoryCandidate>) -> Unit
) {
    suspend fun extractAndSave(
        enabled: Boolean,
        prompt: String,
        modelConfigs: List<ModelConfig>,
        projectId: String,
        conversationId: String,
        userId: String
    ) {
        if (!enabled || !shouldExtractAutomaticMemory(prompt) || !UserMemoryStore.isSafeContent(prompt)) return
        val candidates = runCatchingCancellable { extractCandidates(modelConfigs, listOf(prompt)) }.getOrDefault(emptyList())
        if (candidates.isNotEmpty()) {
            runCatchingCancellable { saveCandidates(projectId, conversationId, userId, candidates) }
        }
    }
}
