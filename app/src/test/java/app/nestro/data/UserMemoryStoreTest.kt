package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMemoryStoreTest {
    private fun memory(
        id: String,
        content: String,
        updatedAt: Long = 1L,
        category: String = "preference",
        confidence: Double = 0.95
    ) = UserMemoryEntity(
        id = id,
        projectId = "project-1",
        category = category,
        content = content,
        fingerprint = id,
        sourceConversationId = "conversation-1",
        sourceMessageId = "message-1",
        confidence = confidence,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    @Test
    fun allowsOrdinaryPreferenceTextThroughLocalPrecheck() {
        assertTrue(UserMemoryStore.isSafeContent("我偏好简洁的界面和直接的表达方式"))
    }

    @Test
    fun blocksCommonSensitiveContentBeforeMemoryExtractionRequest() {
        assertFalse(UserMemoryStore.isSafeContent("我的银行卡号是 6222021234567890123"))
        assertFalse(UserMemoryStore.isSafeContent("我的手机号是 13812345678"))
        assertFalse(UserMemoryStore.isSafeContent("我的收入和负债情况如下"))
        assertFalse(UserMemoryStore.isSafeContent("我的身份证号是 11010119900101123X"))
        assertFalse(UserMemoryStore.isSafeContent("我最近的病史和用药记录"))
    }

    @Test
    fun bm25RanksSpecificChineseMemoryAboveGenericAndOlderMemory() {
        val now = 2_000_000_000_000L
        val generic = memory("generic", "我喜欢简洁表达", updatedAt = now - 400L * 86_400_000L)
        val specific = memory(
            "specific",
            "回答技术问题时先给结论，再解释关键原因",
            updatedAt = now - 86_400_000L,
            category = "constraint",
            confidence = 0.98
        )
        val ranked = MemoryRecallRanker.rank("技术问题先给结论", listOf(generic, specific), now = now)
        assertEquals("specific", ranked.first().id)
    }

    @Test
    fun bm25RejectsUnmatchedMemoriesAndWeakSingleMatchesForLongQueries() {
        val candidate = memory("m1", "项目采用本地优先的存储方式")
        assertTrue(MemoryRecallRanker.rank("完全无关的火星探测", listOf(candidate)).isEmpty())
        assertTrue(
            MemoryRecallRanker.rank(
                "项目使用远程服务器处理完全不同的业务流程",
                listOf(candidate)
            ).isEmpty()
        )
    }

    @Test
    fun bm25DeduplicatesIdenticalContentAfterRanking() {
        val first = memory("first", "我偏好简洁直接的中文回答", updatedAt = 2_000L)
        val duplicate = memory("duplicate", "我偏好简洁直接的中文回答", updatedAt = 1_000L)
        val ranked = MemoryRecallRanker.rank("简洁直接中文回答", listOf(first, duplicate))
        assertEquals(1, ranked.size)
        assertEquals("first", ranked.single().id)
    }

    @Test
    fun semanticRecallFindsParaphrasedMemoryWithoutKeywordOverlap() {
        val relevant = memory("m1", "表达风格应当清楚简洁")
        val unrelated = memory("m2", "旅行时更喜欢自然风景")
        val result = UserMemoryStore.recallSemantic(
            memories = listOf(relevant, unrelated),
            query = "写作口吻",
            queryVector = floatArrayOf(1f, 0f),
            memoryVectors = mapOf(
                "m1" to floatArrayOf(0.99f, 0.01f),
                "m2" to floatArrayOf(0.02f, 0.98f)
            )
        )
        assertEquals(listOf("m1"), result.memories.map { it.id })
        assertTrue(result.context.contains("表达风格应当清楚简洁"))
    }

    @Test
    fun semanticRecallKeepsKeywordFallbackForMissingVectors() {
        val semantic = memory("m1", "沟通表达保持清晰简洁")
        val lexical = memory("m2", "我偏好简洁直接的回答", updatedAt = 2L)
        val result = UserMemoryStore.recallSemantic(
            memories = listOf(semantic, lexical),
            query = "偏好简洁直接",
            queryVector = floatArrayOf(1f, 0f),
            memoryVectors = mapOf("m1" to floatArrayOf(0.98f, 0.02f))
        )
        assertTrue(result.memories.any { it.id == "m1" })
        assertTrue(result.memories.any { it.id == "m2" })
    }

    @Test
    fun invalidSemanticVectorsFallBackToKeywordRecall() {
        val match = memory("m1", "我偏好简洁直接的回答")
        val result = UserMemoryStore.recallSemantic(
            memories = listOf(match),
            query = "偏好简洁直接",
            queryVector = floatArrayOf(Float.NaN, 0f),
            memoryVectors = emptyMap()
        )
        assertEquals(listOf("m1"), result.memories.map { it.id })
    }
    @Test
    fun projectInvalidationRejectsAnInFlightExtractionToken() = kotlinx.coroutines.runBlocking {
        val projectId = "memory-version-project-test"
        val token = UserMemoryStore.mutationVersion(projectId)
        assertTrue(UserMemoryStore.isCurrentMutationVersion(projectId, token))
        UserMemoryStore.invalidateAndAwaitPendingExtractions(projectId)
        assertFalse(UserMemoryStore.isCurrentMutationVersion(projectId, token))
    }

    @Test
    fun globalInvalidationRejectsTokensCapturedBeforeBackupRestore() = kotlinx.coroutines.runBlocking {
        val projectId = "memory-global-version-project-test"
        val token = UserMemoryStore.mutationVersion(projectId)
        assertTrue(UserMemoryStore.isCurrentMutationVersion(projectId, token))
        UserMemoryStore.invalidateAndAwaitPendingExtractions()
        assertFalse(UserMemoryStore.isCurrentMutationVersion(projectId, token))
    }

}
