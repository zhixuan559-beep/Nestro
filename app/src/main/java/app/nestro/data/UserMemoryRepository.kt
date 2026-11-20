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

    suspend fun list(projectId: String): List<UserMemoryEntity> =
        UserMemoryStore.list(database.dao(), projectId)

    suspend fun recall(projectId: String, query: String): MemoryRecall {
        val memories = list(projectId)
        val keywordFallback = UserMemoryStore.recall(memories, query)
        val context = appContext ?: return keywordFallback
        if (memories.isEmpty() || !KnowledgeEmbeddingModelManager.isInstalled(context)) return keywordFallback

        return runCatching {
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
    }

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
