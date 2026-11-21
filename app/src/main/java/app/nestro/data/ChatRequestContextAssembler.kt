package app.nestro.data

import app.nestro.phone.PhoneAgentPlanner
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class ChatRequestContextAssembly(
    val recalledCount: Int,
    val recallSources: List<ConversationRecallSource>,
    val systemContext: String
)

/** Builds the bounded, project-scoped context used by a single chat request. */
internal class ChatRequestContextAssembler(
    private val retrieveMemory: suspend (String, String, String) -> ConversationRecallResult,
    private val retrieveKnowledge: suspend (String, String) -> String,
    private val retrieveUserMemory: suspend (String, String) -> String
) {
    suspend fun assemble(
        projectId: String,
        conversationId: String,
        prompt: String,
        requestHistory: List<ChatMessage>,
        baseSystemContext: String,
        taskModeEnabled: Boolean = false
    ): ChatRequestContextAssembly {
        // These stores are independent. Run only the retrievals selected by policy,
        // concurrently, and keep the final context order deterministic.
        val recallEnabled = ChatContextRetrievalPolicy.shouldRetrieveConversation(prompt)
        val knowledgeEnabled = ChatContextRetrievalPolicy.shouldRetrieveKnowledge(prompt)
        val userMemoryEnabled = ChatContextRetrievalPolicy.shouldRetrieveUserMemory(prompt)
        val recallQuery = requestHistory.takeLast(8)
            .joinToString("\n") { it.content.take(500) }
            .take(2_400)

        return coroutineScope {
            val recallTask = if (recallEnabled) async {
                retrieveMemory(projectId, conversationId, recallQuery)
            } else null
            val knowledgeTask = if (knowledgeEnabled) async {
                retrieveKnowledge(projectId, prompt)
            } else null
            val userMemoryTask = if (userMemoryEnabled) async {
                retrieveUserMemory(projectId, prompt)
            } else null

            val recall = recallTask?.await() ?: ConversationRecallResult("", emptyList())
            val knowledgeContext = knowledgeTask?.await().orEmpty()
            val memoryContext = userMemoryTask?.await().orEmpty()
            val taskContext = if (taskModeEnabled) PhoneAgentPlanner.contextFor(prompt) else ""
            val systemContext = listOf(
                baseSystemContext,
                taskContext,
                recall.context,
                knowledgeContext,
                memoryContext
            ).filter { it.isNotBlank() }.joinToString("\n\n").take(8_000)

            ChatRequestContextAssembly(
                recalledCount = recall.hitCount,
                recallSources = recall.sources,
                systemContext = systemContext
            )
        }
    }
}
