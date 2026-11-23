package app.nestro.data

import android.content.Context

/** Project-scoped application repository for automatic memory operations. */
class UserMemoryRepository(
    private val database: NestroDatabase,
    context: Context? = null
) {
    private val appContext = context?.applicationContext
    private val embeddingCache = object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > 400
    }

    /** Versioned LRU of fully ranked recall results (separate from the embedding-vector cache). */
    private val recallResultCache = object : LinkedHashMap<String, MemoryRecall>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoryRecall>?): Boolean =
            size > 200
    }

    suspend fun list(projectId: String): List<UserMemoryEntity> =
        UserMemoryStore.list(database.dao(), projectId)

    suspend fun recall(
        projectId: String,
        query: String,
        readableSourceProjectIds: Set<String> = emptySet()
    ): MemoryRecall {
        val sourceIds = (readableSourceProjectIds + projectId).distinct().sorted()
        val versionsBeforeRead = sourceIds.joinToString("|") { id ->
            val version = UserMemoryStore.mutationVersion(id)
            id + ":" + version.projectVersion + ":" + version.globalVersion
        }
        val cacheKey = UserMemoryStore.fingerprint(
            projectId + "|" + sourceIds.joinToString(",") + "|" + versionsBeforeRead + "|" + query.trim().lowercase()
        )
        synchronized(recallResultCache) {
            recallResultCache[cacheKey]?.let { return it }
        }

        val memories = database.dao().userMemoriesForRecall(projectId, readableSourceProjectIds.sorted(), 200)
        val keywordFallback = UserMemoryStore.recall(memories, query)
        val context = appContext
        val result = if (context == null || memories.isEmpty() || !KnowledgeEmbeddingModelManager.isInstalled(context)) {
            keywordFallback
        } else runCatching {
            val queryVector = KnowledgeEmbeddingModelManager.embed(context, listOf(query)).single()
            val missing = memories.filter { memory ->
                synchronized(embeddingCache) { memory.fingerprint !in embeddingCache }
            }
            if (missing.isNotEmpty()) {
                val vectors = KnowledgeEmbeddingModelManager.embed(
                    context,
                    missing.map { "${it.category}: ${it.content}" }
                )
                synchronized(embeddingCache) {
                    missing.zip(vectors).forEach { (memory, vector) ->
                        embeddingCache[memory.fingerprint] = vector
                    }
                }
            }
            val vectorsByMemoryId = synchronized(embeddingCache) {
                memories.mapNotNull { memory ->
                    embeddingCache[memory.fingerprint]?.let { memory.id to it }
                }.toMap()
            }
            UserMemoryStore.recallSemantic(memories, query, queryVector, vectorsByMemoryId)
        }.getOrDefault(keywordFallback)

        // Do not publish a result if memory changed while retrieval was running.
        val versionsAfterRead = sourceIds.joinToString("|") { id ->
            val version = UserMemoryStore.mutationVersion(id)
            id + ":" + version.projectVersion + ":" + version.globalVersion
        }
        if (versionsBeforeRead == versionsAfterRead) {
            synchronized(recallResultCache) { recallResultCache[cacheKey] = result }
        }
        return result
    }

    suspend fun saveCandidates(
        projectId: String,
        conversationId: String,
        messageId: String,
        candidates: List<MemoryCandidate>,
        expectedMutationVersion: MemoryMutationToken? = null
    ): Int = UserMemoryStore.saveCandidates(
        database.dao(), projectId, conversationId, messageId, candidates, expectedMutationVersion
    )

    suspend fun edit(id: String, projectId: String, content: String, category: String) {
        UserMemoryStore.edit(database.dao(), id, projectId, content, category)
        synchronized(embeddingCache) { embeddingCache.clear() }
    }

    suspend fun saveManual(projectId: String, content: String, category: String = "preference"): Boolean =
        UserMemoryStore.saveManual(database.dao(), projectId, content, category)

    suspend fun importOne(projectId: String, rawJson: String): Boolean {
        val imported = UserMemoryExportCodec.decode(rawJson, projectId)
        val changed = UserMemoryStore.importOne(database.dao(), projectId, imported)
        if (changed) synchronized(embeddingCache) { embeddingCache.clear() }
        return changed
    }

    suspend fun setShareAcrossProjects(id: String, ownerProjectId: String, enabled: Boolean): Boolean {
        val changed = UserMemoryStore.setShareAcrossProjects(database.dao(), id, ownerProjectId, enabled)
        if (changed) synchronized(embeddingCache) { embeddingCache.clear() }
        return changed
    }

    suspend fun invalidatePendingExtractions(projectId: String) =
        UserMemoryStore.invalidateAndAwaitPendingExtractions(projectId)

    suspend fun clearProject(projectId: String) {
        UserMemoryStore.clearProject(database.dao(), projectId)
        synchronized(embeddingCache) { embeddingCache.clear() }
    }

    suspend fun delete(id: String, projectId: String) {
        UserMemoryStore.delete(database.dao(), id, projectId)
        synchronized(embeddingCache) { embeddingCache.clear() }
    }
}
