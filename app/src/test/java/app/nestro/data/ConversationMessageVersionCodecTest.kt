package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationMessageVersionCodecTest {
    @Test
    fun roundTripsEveryValidMessageVersionAndActiveIndex() {
        val message = ChatMessage("m1", "assistant", "当前回答", listOf("旧回答", "当前回答"), 1)
        assertEquals("[\"旧回答\",\"当前回答\"]", encodeMessageVersions(message))
        assertEquals(message.versions, decodeMessageVersions(encodeMessageVersions(message), 1))
    }

    @Test
    fun rejectsMalformedJsonAndNonStringVersionEntries() {
        assertThrows(Exception::class.java) { decodeMessageVersions("{invalid", 0) }
        assertThrows(IllegalArgumentException::class.java) { decodeMessageVersions("[\"ok\",7]", 0) }
    }

    @Test
    fun rejectsTooManyOrOversizedVersionsInsteadOfTruncating() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeMessageVersions(ChatMessage("m1", "assistant", "answer", List(21) { "v$it" }, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeMessageVersions(org.json.JSONArray().apply { repeat(21) { put("v$it") } }.toString(), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeMessageVersions(org.json.JSONArray().put("x".repeat(200_001)).toString(), 0)
        }
    }

    @Test
    fun rejectsInvalidActiveVersionInsteadOfClampingIt() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeMessageVersions(ChatMessage("m1", "assistant", "answer", listOf("v1"), 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeMessageVersions("[\"v1\"]", 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeMessageVersions("[]", -1)
        }
    }
    @Test
    fun validatesConversationAndMessageIdentityBeforePersistence() {
        val valid = ConversationRecord(
            "c1", "对话", 1L,
            listOf(ChatMessage("m1", "user", "有效消息")),
            "default"
        )
        validateConversationRecords(listOf(valid))

        assertThrows(IllegalArgumentException::class.java) {
            validateConversationRecords(listOf(valid, valid))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateConversationRecords(listOf(
                valid,
                ConversationRecord("c2", "另一对话", 2L, listOf(ChatMessage("m1", "assistant", "重复消息")), "default")
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateChatMessages(listOf(ChatMessage("m1", "tool", "不允许的角色")))
        }
    }

    @Test
    fun enforcesPerConversationSizeLimitForRoomBackedPersistence() {
        val oversized = (0 until 18).map { index ->
            ChatMessage("large-$index", if (index % 2 == 0) "user" else "assistant", "x".repeat(200_000))
        }
        assertThrows(IllegalArgumentException::class.java) { validateChatMessages(oversized) }
    }

    @Test
    fun RoomSnapshotsDoNotInheritTheLegacySharedPreferencesAggregateCap() {
        val conversations = listOf(
            ConversationRecord(
                "c1", "第一段", 1L,
                (0 until 10).map { ChatMessage("a-$it", "user", "a".repeat(200_000)) },
                "default"
            ),
            ConversationRecord(
                "c2", "第二段", 2L,
                (0 until 10).map { ChatMessage("b-$it", "user", "b".repeat(200_000)) },
                "default"
            )
        )
        validateConversationRecords(conversations)
        assertThrows(IllegalArgumentException::class.java) {
            validateLegacyConversationSnapshot(conversations)
        }
    }

}
