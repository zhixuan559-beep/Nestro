package app.nestro.data

import android.content.Context

/** Project-scoped retrieval boundary for conversation history and knowledge. */
class ConversationContextRepository(private val database: NestroDatabase, private val context: Context? = null) {
    suspend fun retrieveMemory(
        projectId: String,
        conversationId: String,
        query: String
    ): ConversationRecallResult =
        ConversationMemoryStore.retrieve(database.dao(), projectId, conversationId, query)

    suspend fun retrieveKnowledge(projectId: String, query: String): String =
        KnowledgeStore.retrieve(database.dao(), projectId, query, context)

    /** Changes whenever the other conversations in this project are added, edited, or removed. */
    suspend fun recallVersion(projectId: String, currentConversationId: String): String =
        database.dao().conversationRecallCorpusVersion(projectId, currentConversationId)
}
