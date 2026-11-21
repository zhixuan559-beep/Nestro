package app.nestro.data

import app.nestro.phone.PhoneAgentPlanner

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
        val recall = if (ChatContextRetrievalPolicy.shouldRetrieveConversation(prompt)) {
            val recallQuery = requestHistory.takeLast(8)
                .joinToString("\n") { it.content.take(500) }
                .take(2400)
            retrieveMemory(projectId, conversationId, recallQuery)
        } else {
            ConversationRecallResult("", emptyList())
        }
        val knowledgeContext = if (ChatContextRetrievalPolicy.shouldRetrieveKnowledge(prompt)) {
            retrieveKnowledge(projectId, prompt)
        } else ""
        val memoryContext = if (ChatContextRetrievalPolicy.shouldRetrieveUserMemory(prompt)) {
            retrieveUserMemory(projectId, prompt)
        } else ""
        val taskContext = if (taskModeEnabled) PhoneAgentPlanner.contextFor(prompt) else ""
        val systemContext = listOf(
            baseSystemContext,
            taskContext,
            recall.context,
            knowledgeContext,
            memoryContext
        ).filter { it.isNotBlank() }.joinToString("\n\n").take(8_000)
        return ChatRequestContextAssembly(
            recalledCount = recall.hitCount,
            recallSources = recall.sources,
            systemContext = systemContext
        )
    }
}
