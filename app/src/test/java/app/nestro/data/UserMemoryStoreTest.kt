package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMemoryStoreTest {
    @Test
    fun allowsOrdinaryPreferenceTextThroughLocalPrecheck() {
        assertTrue(UserMemoryStore.isSafeContent("我偏好简洁的界面和直接的表达方式"))
    }

    @Test
    fun ranksSpecificMemoriesUsingChineseNgramsAndConfidence() {
        val now = 2_000_000_000_000L
        val generic = UserMemoryEntity("generic", "p1", "preference", "我喜欢简洁表达", "g", "c1", "m1", 0.9, now - 400L * 86_400_000L, now - 400L * 86_400_000L)
        val specific = UserMemoryEntity("specific", "p1", "constraint", "回答技术问题时先给结论，再解释关键原因", "s", "c2", "m2", 0.95, now - 86_400_000L, now - 86_400_000L)
        val ranked = MemoryRecallRanker.rank("技术问题先给结论", listOf(generic, specific), now = now)
        assertEquals("specific", ranked.first().id)
    }

    @Test
    fun recallDoesNotReturnUnmatchedMemories() {
        val memory = UserMemoryEntity("m1", "p1", "preference", "我偏好简洁直接的表达", "f", "c1", "msg1", 0.9, 1, 1)
        assertTrue(MemoryRecallRanker.rank("完全无关的火星探测", listOf(memory)).isEmpty())
    }

    @Test
    fun blocksCommonSensitiveContentBeforeMemoryExtractionRequest() {
        assertFalse(UserMemoryStore.isSafeContent("我的银行卡号是 6222021234567890123"))
        assertFalse(UserMemoryStore.isSafeContent("我的手机号是 13812345678"))
        assertFalse(UserMemoryStore.isSafeContent("我的收入和负债情况如下"))
    }
}
