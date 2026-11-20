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
        assertEquals("memory-1", json.getString("id"))
        assertEquals("project-1", json.getString("project_id"))
        assertEquals("偏好清晰直接的表达方式", json.getString("content"))
        assertEquals("conversation-1", json.getString("source_conversation_id"))
        assertEquals("message-1", json.getString("source_message_id"))
        assertEquals(0.93, json.getDouble("confidence"), 0.0001)
        assertEquals(100L, json.getLong("created_at"))
        assertEquals(200L, json.getLong("updated_at"))
    }

    @Test
    fun refusesToExportSensitiveContentWeakConfidenceOrMissingProvenance() {
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.encode(memory().copy(content = "我的银行卡号是 6222021234567890123"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.encode(memory().copy(confidence = 0.79))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.encode(memory().copy(sourceMessageId = ""))
        }
    }

    @Test
    fun importsPortableMemoryIntoTargetProjectWithFreshIdentity() {
        val imported = UserMemoryExportCodec.decode(UserMemoryExportCodec.encode(memory()), "target-project")
        assertEquals("target-project", imported.projectId)
        assertEquals("preference", imported.category)
        assertEquals("偏好清晰直接的表达方式", imported.content)
        assertEquals("conversation-1", imported.sourceConversationId)
        assertEquals("message-1", imported.sourceMessageId)
        assertEquals(0.93, imported.confidence, 0.0001)
        assertEquals(100L, imported.createdAt)
        assertEquals(200L, imported.updatedAt)
        org.junit.Assert.assertNotEquals("memory-1", imported.id)
    }

    @Test
    fun rejectsUnsupportedVersionAndWrongFormatOnImport() {
        val valid = JSONObject(UserMemoryExportCodec.encode(memory()))
        valid.put("version", 99)
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.decode(valid.toString(), "target-project")
        }
        valid.put("version", UserMemoryExportCodec.VERSION)
        valid.put("format", "other-format")
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.decode(valid.toString(), "target-project")
        }
    }

    @Test
    fun rejectsSensitiveAndOversizedImportPayloads() {
        val sensitive = JSONObject(UserMemoryExportCodec.encode(memory()))
        sensitive.put("content", "我的银行卡号是 6222021234567890123")
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.decode(sensitive.toString(), "target-project")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserMemoryExportCodec.decode(" ".repeat(32_769), "target-project")
        }
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
