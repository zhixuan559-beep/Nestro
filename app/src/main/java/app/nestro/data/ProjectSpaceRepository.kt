package app.nestro.data

/** Owns project-space deletion checks and Room-backed relationship queries. */
class ProjectSpaceRepository(private val database: NestroDatabase) {
    suspend fun conversationIds(projectId: String): List<String> =
        database.dao().projectConversationIds(projectId)

    suspend fun hasLinkedData(projectId: String): Boolean =
        database.dao().projectHasLinkedData(projectId)

    suspend fun exists(projectId: String): Boolean =
        database.dao().projectExists(projectId)

    suspend fun deleteIfEmpty(projectId: String, activeProjectId: String): Boolean =
        database.dao().deleteProjectIfEmpty(projectId, activeProjectId)

    suspend fun duplicate(
        sourceProjectId: String,
        newProjectId: String,
        newName: String,
        createdAt: Long
    ): Boolean = database.dao().duplicateProject(sourceProjectId, newProjectId, newName, createdAt)

    suspend fun rollbackDuplicate(projectId: String) =
        database.dao().rollbackProjectDuplicate(projectId)
}
