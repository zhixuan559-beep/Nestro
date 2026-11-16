package app.nestro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMemoryStoreTest {
    @Test
    fun allowsOrdinaryPreferenceTextThroughLocalPrecheck() {
        assertTrue(UserMemoryStore.isSafeContent("我偏好简洁的界面和直接的表达方式"))
    }

    @Test
    fun blocksCommonSensitiveContentBeforeMemoryExtractionRequest() {
        assertFalse(UserMemoryStore.isSafeContent("我的银行卡号是 6222021234567890123"))
        assertFalse(UserMemoryStore.isSafeContent("我的手机号是 13812345678"))
        assertFalse(UserMemoryStore.isSafeContent("我的收入和负债情况如下"))
    }
}
