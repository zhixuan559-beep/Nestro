package app.nestro.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.LinkedHashMap

internal data class ChatRequestContextAssembly(
    val recalledCount: Int,
    val recallSources: List<ConversationRecallSource>,
    val systemContext: String
)

/** Builds the bounded, project-scoped context used by a single chat request. */
internal class ChatRequestContextAssembler(
    private val retrieveMemory: suspend (String, String, String) -> ConversationRecallResult,
    private val retrieveUserMemory: suspend (String, String) -> String,
    private val cacheTtlMillis: Long = 60_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class CachedAssembly(val storedAt: Long, val value: ChatRequestContextAssembly)
    private val assemblyCache = object : LinkedHashMap<String, CachedAssembly>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAssembly>?): Boolean =
            size > 64
    }

    suspend fun assemble(
        projectId: String,
        conversationId: String,
        prompt: String,
        requestHistory: List<ChatMessage>,
        baseSystemContext: String
    ): ChatRequestContextAssembly {
        // These stores are independent. Run only the retrievals selected by policy,
        // concurrently, and keep the final context order deterministic.
        val recallEnabled = ChatContextRetrievalPolicy.shouldRetrieveConversation(prompt)
        val userMemoryEnabled = ChatContextRetrievalPolicy.shouldRetrieveUserMemory(prompt)
        val recallQuery = requestHistory.takeLast(8)
            .joinToString("\n") { it.content.take(500) }
            .take(2_400)
        val userMemoryQuery = buildMemoryRecallQuery(
            requestHistory.filter { it.role == "user" }.takeLast(7).map { it.content },
            prompt
        )
        // Cache the final assembled prompt context, not merely embeddings. The key
        // covers every input that affects retrieval or the final system context.
        val cacheKey = UserMemoryStore.fingerprint(buildString {
            append(projectId).append('|').append(conversationId).append('|')
            append(prompt).append('|').append(recallEnabled).append('|').append(userMemoryEnabled).append('|')
            append(baseSystemContext).append('|').append(recallQuery).append('|').append(userMemoryQuery)
        })
        synchronized(assemblyCache) {
            val cached = assemblyCache[cacheKey]
            if (cached != null && nowMillis() - cached.storedAt in 0..cacheTtlMillis) return cached.value
            if (cached != null) assemblyCache.remove(cacheKey)
        }

        val assembled = coroutineScope {
            val recallTask = if (recallEnabled) async {
                retrieveMemory(projectId, conversationId, recallQuery)
            } else null
            val userMemoryTask = if (userMemoryEnabled) async {
                retrieveUserMemory(projectId, userMemoryQuery)
            } else null

            val recall = recallTask?.await() ?: ConversationRecallResult("", emptyList())
            val memoryContext = userMemoryTask?.await().orEmpty()
            val systemContext = listOf(
                baseSystemContext,
                recall.context,
                memoryContext
            ).filter { it.isNotBlank() }.joinToString("\n\n").take(8_000)

            ChatRequestContextAssembly(
                recalledCount = recall.hitCount,
                recallSources = recall.sources,
                systemContext = systemContext
            )
        }
        synchronized(assemblyCache) {
            assemblyCache[cacheKey] = CachedAssembly(nowMillis(), assembled)
        }
        return assembled
    }
}
