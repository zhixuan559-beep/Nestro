package app.nestro.data

import app.nestro.agent.AgentToolResult
import app.nestro.model.ChatStreamingCoordinator

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
    val expertTeamEnabled: Boolean = false,
    val parentTaskId: String? = null,
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
        planPhoneAgent: suspend (Map<String, String>) -> AgentToolResult = { AgentToolResult(false, error = "Phone Agent 任务规划回调未配置") },
        onTaskPlanningFailure: (Throwable) -> Unit = {},
        runExpertTeam: suspend (String, Map<String, String>) -> String = { _, _ -> "" },
        onPartialAnswer: suspend (String) -> Unit
    ): ChatRequestExecutionResult {
        var recalledCount = 0
        var recallSources: List<ConversationRecallSource> = emptyList()
        val partialAnswer = StringBuilder()
        var taskPlanningFailure: Throwable? = null
        val result = runCatchingCancellable {
            // Context retrieval is selected by policy; task planning is deferred until
            // the model explicitly selects the phone_agent.execute capability.
            val assembled = contextAssembler.assemble(
                projectId = request.projectId,
                conversationId = request.conversationId,
                prompt = request.prompt,
                requestHistory = request.history,
                baseSystemContext = request.baseSystemContext
            )
            recalledCount = assembled.recalledCount
            recallSources = assembled.recallSources

            val finalSystemContext = assembled.systemContext.take(24_000)

            if (request.shouldStop()) throw IllegalStateException("用户停止生成")
            var directPersistedLength = 0
            var lastDirectPersistAt = System.currentTimeMillis()
            val coordinated = modelToolDispatcher?.let { dispatcher ->
                try {
                    dispatcher.dispatch(
                        requestId = request.requestId,
                        conversationId = request.conversationId,
                        projectId = request.projectId,
                        prompt = request.prompt,
                        history = request.history,
                        systemContext = finalSystemContext,
                        configs = request.modelConfigs,
                        temperature = request.temperature,
                        maxTokens = request.maxTokens,
                        shouldStop = request.shouldStop,
                        taskModeEnabled = request.taskModeEnabled,
                        expertTeamEnabled = request.expertTeamEnabled,
                        parentTaskId = request.parentTaskId,
                        planPhoneAgent = { arguments ->
                            try {
                                planPhoneAgent(arguments)
                            } catch (error: Throwable) {
                                taskPlanningFailure = error
                                onTaskPlanningFailure(error)
                                throw error
                            }
                        },
                        runExpertTeam = runExpertTeam,
                        onUsage = onUsage,
                        onProviderUsage = onProviderUsage,
                        onDirectAnswer = { partial ->
                            partialAnswer.setLength(0)
                            partialAnswer.append(partial)
                            val now = System.currentTimeMillis()
                            if (partial.length - directPersistedLength >= 256 || now - lastDirectPersistAt >= 1_500L) {
                                streamingCoordinator.persistPartial(
                                    requestId = request.requestId,
                                    conversationId = request.conversationId,
                                    projectId = request.projectId,
                                    prompt = request.prompt,
                                    partialAnswer = partial,
                                    startedAt = request.startedAt
                                )
                                directPersistedLength = partial.length
                                lastDirectPersistAt = now
                            }
                            onPartialAnswer(partial)
                        }
                    )
                } finally {
                    dispatcher.endApprovalScope(request.requestId)
                }
            }
            if (coordinated?.directReply != null) {
                streamingCoordinator.persistPartial(
                    requestId = request.requestId,
                    conversationId = request.conversationId,
                    projectId = request.projectId,
                    prompt = request.prompt,
                    partialAnswer = coordinated.directReply,
                    startedAt = request.startedAt
                )
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
