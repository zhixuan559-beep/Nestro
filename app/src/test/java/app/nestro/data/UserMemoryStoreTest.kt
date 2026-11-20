package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMemoryStoreTest {
    private fun memory(id: String, content: String, updatedAt: Long = 1L) = UserMemoryEntity(
        id = id,
        projectId = "project-1",
        category = "preference",
        content = content,
        fingerprint = id,
        sourceConversationId = "conversation-1",
        sourceMessageId = "message-1",
        confidence = 0.95,
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
}
