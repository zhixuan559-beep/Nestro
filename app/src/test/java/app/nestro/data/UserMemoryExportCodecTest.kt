package app.nestro.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UserMemoryExportCodecTest {
    private fun memory() = UserMemoryEntity(
        id = "memory-1",
        projectId = "project-1",
        category = "preference",
        content = "偏好清晰直接的表达方式",
        fingerprint = "fingerprint",
        sourceConversationId = "conversation-1",
        sourceMessageId = "message-1",
        confidence = 0.93,
        createdAt = 100L,
        updatedAt = 200L
    )

    @Test
    fun exportsSingleMemoryWithProvenanceAndConfidence() {
        val json = JSONObject(UserMemoryExportCodec.encode(memory()))
        assertEquals(UserMemoryExportCodec.FORMAT, json.getString("format"))
        assertEquals(UserMemoryExportCodec.VERSION, json.getInt("version"))
        assertEquals("偏好清晰直接的表达方式", json.getString("content"))
        assertEquals("conversation-1", json.getString("source_conversation_id"))
        assertEquals("message-1", json.getString("source_message_id"))
        assertEquals(0.93, json.getDouble("confidence"), 0.0001)
        assertEquals(100L, json.getLong("created_at"))
        assertEquals(200L, json.getLong("updated_at"))
    }

    @Test
    fun refusesToExportInvalidCategoryOrTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.encode(memory().copy(category = "secret"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.encode(memory().copy(updatedAt = 50L))
        }
    }
}
