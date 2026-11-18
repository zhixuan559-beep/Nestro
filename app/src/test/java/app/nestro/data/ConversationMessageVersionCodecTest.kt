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
            decodeMessageVersions(org.json.JSONArray(List(21) { "v$it" }.toString()).toString(), 0)
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
}
