package app.nestro.model

import app.nestro.data.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class ModelComparisonResult(
    val modelId: String,
    val modelName: String,
    val response: String,
    val error: String? = null
)

internal object ModelComparisonPolicy {
    fun validate(registry: ModelRegistry, firstModelId: String, secondModelId: String, prompt: String): String? {
        if (firstModelId.isBlank() || secondModelId.isBlank()) return "请选择两个模型"
        if (firstModelId == secondModelId) return "请选择两个不同的模型"
        val selectable = registry.selectableModels().mapTo(mutableSetOf()) { it.id }
        if (firstModelId !in selectable || secondModelId !in selectable) return "所选模型不可用，请检查服务商、模型状态和 API Key"
        if (prompt.isBlank()) return "请输入需要对比的内容"
        if (prompt.length > 12_000) return "对比内容不能超过 12000 个字符"
        return null
    }
}

internal class ModelComparisonCoordinator(
    private val repository: ModelConfigurationRepository
) {
    private val execution = ChatExecutionService()
    private val activeRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun cancel() {
        activeRequestIds.toList().forEach(execution::cancel)
    }

    suspend fun compare(
        registry: ModelRegistry,
        firstModelId: String,
        secondModelId: String,
        prompt: String,
        systemPrompt: String,
        reasoningEffort: String,
        onFirstChunk: suspend (String) -> Unit,
        onSecondChunk: suspend (String) -> Unit
    ): Pair<ModelComparisonResult, ModelComparisonResult> {
        ModelComparisonPolicy.validate(registry, firstModelId, secondModelId, prompt)?.let {
            throw IllegalArgumentException(it)
        }
        val models = registry.selectableModels().associateBy { it.id }
        val first = models.getValue(firstModelId)
        val second = models.getValue(secondModelId)
        val history = listOf(ChatMessage(UUID.randomUUID().toString(), "user", prompt.trim()))

        suspend fun runOne(
            modelId: String,
            modelName: String,
            onChunk: suspend (String) -> Unit
        ): ModelComparisonResult {
            val requestId = "compare-$modelId-${UUID.randomUUID()}"
            activeRequestIds.add(requestId)
            val partial = StringBuilder()
            return try {
                val configs = withContext(Dispatchers.IO) {
                    ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
                        registry = registry,
                        activeModelId = modelId,
                        failoverEnabled = false,
                        reasoningEffort = reasoningEffort,
                        readKey = repository::readApiKey
                    )
                }
                if (configs.isEmpty()) return ModelComparisonResult(modelId, modelName, "", "没有可用的 API Key")
                val answer = execution.streamWithFallback(
                    configs = configs,
                    history = history,
                    systemPrompt = systemPrompt,
                    maxTokens = 2048,
                    requestId = requestId,
                    onChunk = { chunk ->
                        partial.append(chunk)
                        withContext(Dispatchers.Main.immediate) { onChunk(chunk) }
                    }
                )
                ModelComparisonResult(modelId, modelName, answer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ModelComparisonResult(
                    modelId,
                    modelName,
                    partial.toString(),
                    error.message?.take(500)?.ifBlank { "模型请求失败" } ?: "模型请求失败"
                )
            } finally {
                activeRequestIds.remove(requestId)
            }
        }

        return coroutineScope {
            val firstRequest = async { runOne(first.id, first.name, onFirstChunk) }
            val secondRequest = async { runOne(second.id, second.name, onSecondChunk) }
            firstRequest.await() to secondRequest.await()
        }
    }
}
