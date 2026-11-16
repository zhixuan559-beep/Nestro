package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMemoryStoreTest {
    private fun memory(id: String, content: String, category: String = "preference", confidence: Double = 0.9, updatedAt: Long = 1_000L) =
        UserMemoryEntity(id, "p1", category, content, "fp-$id", "c1", "m-$id", confidence, updatedAt, updatedAt)

    @Test fun allowsOrdinaryPreferenceTextThroughLocalPrecheck() {
        assertTrue(UserMemoryStore.isSafeContent("我偏好简洁的界面和直接的表达方式"))
        assertTrue(UserMemoryStore.isSafeContent("我偏好使用本地 SQLite 数据库存储项目配置"))
    }

    @Test fun ranksSpecificMemoriesUsingChineseNgramsAndConfidence() {
        val now = 2_000_000_000_000L
        val generic = memory("generic", "我喜欢简洁表达", updatedAt = now - 400L * 86_400_000L)
        val specific = memory("specific", "回答技术问题时先给结论，再解释关键原因", "constraint", 0.95, now - 86_400_000L)
        val ranked = MemoryRecallRanker.rank("技术问题先给结论", listOf(generic, specific), now = now)
        assertEquals("specific", ranked.first().id)
    }

    @Test fun recallDoesNotReturnUnmatchedMemories() {
        assertTrue(MemoryRecallRanker.rank("完全无关的火星探测", listOf(memory("m1", "我偏好简洁直接的表达"))).isEmpty())
    }

    @Test fun recallQueryAlwaysPreservesCurrentPromptWhenHistoryIsLarge() {
        val query = UserMemoryStore.buildRecallQuery(
            listOf("旧历史内容".repeat(500), "中间历史".repeat(500)),
            "当前问题必须保留在检索词中",
            maxChars = 80
        )
        assertTrue(query.length <= 80)
        assertTrue(query.contains("当前问题必须保留在检索词中"))
    }

    @Test fun extractionUsesSafeRecentTurnsAndCanTriggerFromEarlierTurn() {
        val input = UserMemoryStore.prepareExtractionInput(listOf(
            "我偏好以后所有技术回答先给结论，再解释原因",
            "我的银行卡号是 6222021234567890123",
            "请帮我看看这段代码"
        ))
        assertEquals(2, input.size)
        assertTrue(UserMemoryStore.shouldExtractFrom(input))
        assertFalse(UserMemoryStore.prepareExtractionInput(listOf("我的手机号是 13812345678")).isNotEmpty())
        assertFalse(UserMemoryStore.shouldExtractFrom(listOf("请帮我看看这段代码")))
    }

    @Test fun recallContextUsesRealLineBreaksAndHonorsEmptyInput() {
        assertEquals("", UserMemoryStore.buildContext("p1", emptyList()))
        val context = UserMemoryStore.buildContext("p1", listOf(memory("m1", "回答时先给结论"), memory("m2", "偏好本地优先")))
        assertTrue(context.contains("\n"))
        assertFalse(context.contains("\\n"))
        assertTrue(context.contains("回答时先给结论"))
        assertTrue(context.contains("偏好本地优先"))
    }

    @Test fun deduplicatesIdenticalMemoryContentAfterRanking() {
        val first = memory("first", "我偏好简洁直接的中文回答", updatedAt = 2_000L)
        val duplicate = memory("duplicate", "我偏好简洁直接的中文回答", updatedAt = 1_000L)
        val ranked = MemoryRecallRanker.rank("简洁直接中文回答", listOf(first, duplicate))
        assertEquals(1, ranked.size)
        assertEquals("first", ranked.single().id)
    }

    @Test fun blocksPersonalIdentifiersCredentialsAndFinancialOrMedicalDetails() {
        assertFalse(UserMemoryStore.isSafeContent("我的银行卡号是 6222021234567890123"))
        assertFalse(UserMemoryStore.isSafeContent("我的手机号是 13812345678"))
        assertFalse(UserMemoryStore.isSafeContent("我的收入和负债情况如下"))
        assertFalse(UserMemoryStore.isSafeContent("项目密钥是 sk-12345678901234567890abcdef"))
        assertFalse(UserMemoryStore.isSafeContent("请记住我的 API key"))
        assertFalse(UserMemoryStore.isSafeContent("我最近的病史需要长期记录"))
    }
}
