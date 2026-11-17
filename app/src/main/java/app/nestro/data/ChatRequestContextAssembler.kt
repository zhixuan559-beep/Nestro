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
        baseSystemContext: String
    ): ChatRequestContextAssembly {
        val recallQuery = requestHistory.takeLast(8)
            .joinToString("\n") { it.content.take(500) }
            .take(2400)
        val recall = retrieveMemory(projectId, conversationId, recallQuery)
        val knowledgeContext = retrieveKnowledge(projectId, prompt)
        val memoryContext = retrieveUserMemory(projectId, prompt)
        val systemContext = listOf(
            baseSystemContext,
            PhoneAgentPlanner.contextFor(prompt),
            recall.context,
            knowledgeContext,
            memoryContext
        ).filter { it.isNotBlank() }.joinToString("\n\n").take(16_000)
        return ChatRequestContextAssembly(
            recalledCount = recall.hitCount,
            recallSources = recall.sources,
            systemContext = systemContext
        )
    }
}
