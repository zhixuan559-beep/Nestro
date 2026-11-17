package app.nestro.data

/** Project-scoped retrieval boundary for conversation history and knowledge. */
class ConversationContextRepository(private val database: NestroDatabase) {
    suspend fun retrieveMemory(
        projectId: String,
        conversationId: String,
        query: String
    ): ConversationRecallSource =
        ConversationMemoryStore.retrieve(database.dao(), projectId, conversationId, query)

    suspend fun retrieveKnowledge(projectId: String, query: String): String =
        KnowledgeStore.retrieve(database.dao(), projectId, query)
}
