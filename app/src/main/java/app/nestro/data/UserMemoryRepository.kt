package app.nestro.data

/** Project-scoped application repository for automatic memory operations. */
class UserMemoryRepository(private val database: NestroDatabase) {
    suspend fun list(projectId: String): List<UserMemoryEntity> =
        UserMemoryStore.list(database.dao(), projectId)

    suspend fun recall(projectId: String, query: String): MemoryRecall =
        UserMemoryStore.recall(database.dao(), projectId, query)

    suspend fun saveCandidates(
        projectId: String,
        conversationId: String,
        messageId: String,
        candidates: List<MemoryCandidate>
    ): Int = UserMemoryStore.saveCandidates(
        database.dao(), projectId, conversationId, messageId, candidates
    )

    suspend fun edit(id: String, projectId: String, content: String, category: String) =
        UserMemoryStore.edit(database.dao(), id, projectId, content, category)

    suspend fun delete(id: String, projectId: String) =
        UserMemoryStore.delete(database.dao(), id, projectId)
}
