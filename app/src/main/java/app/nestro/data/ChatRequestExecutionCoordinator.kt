package app.nestro.data

import app.nestro.model.ChatStreamingCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class ChatRequestExecutionRequest(
    val requestId: String,
    val conversationId: String,
    val projectId: String,
    val prompt: String,
    val startedAt: Long,
    val history: List<ChatMessage>,
    val modelConfigs: List<ModelConfig>,
    val baseSystemContext: String,
    val taskModeEnabled: Boolean,
    val temperature: Double?,
    val maxTokens: Int?,
    val previousAssistant: ChatMessage?,
    val shouldStop: () -> Boolean
)

internal data class ChatRequestExecutionResult(
    val finalization: ChatResponseFinalization,
    val recalledCount: Int,
    val recallSources: List<ConversationRecallSource>,
    val taskPlanningFailure: Throwable?
)

/**
 * Owns the sequential chat request pipeline. UI-specific task/team progress and
 * partial-answer rendering are supplied as callbacks; request policy, context
 * assembly, streaming and terminal response rules stay in one application boundary.
 */
internal class ChatRequestExecutionCoordinator(
    private val contextAssembler: ChatRequestContextAssembler,
    private val streamingCoordinator: ChatStreamingCoordinator,
    private val modelToolDispatcher: ModelLedToolDispatcher? = null
) {
    suspend fun execute(
        request: ChatRequestExecutionRequest,
        onUsage: (ModelConfig, Int, Int) -> Unit,
        onProviderUsage: (ModelConfig, Int, Int) -> Unit,
        planPhoneAgent: suspend () -> Unit = {},
        onTaskPlanningFailure: (Throwable) -> Unit = {},
        runExpertTeam: suspend (String) -> String = { "" },
        onPartialAnswer: suspend (String) -> Unit
    ): ChatRequestExecutionResult {
        var recalledCount = 0
        var recallSources: List<ConversationRecallSource> = emptyList()
        val partialAnswer = StringBuilder()
        var taskPlanningFailure: Throwable? = null
        val result = runCatchingCancellable {
            // Planning and context retrieval are independent. Overlap them so
            // optional task preparation does not add its full latency to retrieval.
            val assembled = coroutineScope {
                val planning = if (request.taskModeEnabled) async {
                    runCatchingCancellable { planPhoneAgent() }
                        .onFailure {
                            taskPlanningFailure = it
                            onTaskPlanningFailure(it)
                        }
                } else null

                val context = contextAssembler.assemble(
                    projectId = request.projectId,
                    conversationId = request.conversationId,
                    prompt = request.prompt,
                    requestHistory = request.history,
                    baseSystemContext = request.baseSystemContext,
                    taskModeEnabled = request.taskModeEnabled
                )
                planning?.await()
                context
            }
            recalledCount = assembled.recalledCount
            recallSources = assembled.recallSources

            val teamContext = runExpertTeam(assembled.systemContext)
            val finalSystemContext = listOf(assembled.systemContext, teamContext)
                .filter(String::isNotBlank)
                .joinToString("\n\n")
                .take(24_000)

            if (request.shouldStop()) throw IllegalStateException("用户停止生成")
            val coordinated = modelToolDispatcher?.let { dispatcher ->
                runCatchingCancellable {
                    dispatcher.dispatch(
                        requestId = request.requestId,
                        projectId = request.projectId,
                        prompt = request.prompt,
                        history = request.history,
                        systemContext = finalSystemContext,
                        configs = request.modelConfigs,
                        temperature = request.temperature,
                        maxTokens = request.maxTokens,
                        shouldStop = request.shouldStop,
                        onUsage = onUsage,
                        onProviderUsage = onProviderUsage,
                        onDirectAnswer = { partial ->
                            partialAnswer.setLength(0)
                            partialAnswer.append(partial)
                            onPartialAnswer(partial)
                        }
                    )
                }.getOrNull()
            }
            if (coordinated?.directReply != null) {
                if (!coordinated.streamedToUi) {
                    partialAnswer.setLength(0)
                    partialAnswer.append(coordinated.directReply)
                    onPartialAnswer(coordinated.directReply)
                }
                coordinated.directReply
            } else {
            partialAnswer.setLength(0)
            val streamed = streamingCoordinator.execute(
                configs = request.modelConfigs,
                history = ChatRequestPolicy.boundedChatHistory(request.history, finalSystemContext),
                systemPrompt = finalSystemContext,
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                onUsage = onUsage,
                onProviderUsage = onProviderUsage,
                requestId = request.requestId,
                conversationId = request.conversationId,
                projectId = request.projectId,
                prompt = request.prompt,
                startedAt = request.startedAt,
                shouldStop = request.shouldStop,
                onPartialAnswer = { partial ->
                    partialAnswer.setLength(0)
                    partialAnswer.append(partial)
                    onPartialAnswer(partial)
                }
            )
            partialAnswer.setLength(0)
            partialAnswer.append(streamed.partialAnswer)
            streamed.failure?.let { throw it }
            streamed.response.orEmpty()
            }
        }

        return ChatRequestExecutionResult(
            finalization = ChatResponseFinalizer.build(
                result = result,
                partialAnswer = partialAnswer.toString(),
                stopRequested = request.shouldStop(),
                previousAssistant = request.previousAssistant,
                requestHistory = request.history,
                assistantId = request.requestId
            ),
            recalledCount = recalledCount,
            recallSources = recallSources,
            taskPlanningFailure = taskPlanningFailure
        )
    }
}
