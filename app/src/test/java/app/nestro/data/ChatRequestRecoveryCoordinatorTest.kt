package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestRecoveryCoordinatorTest {
    @Test
    fun malformedRequestMarkerIsIgnored() {
        assertNull(parsePendingChatRequest("{not-json"))
    }

    @Test
    fun nonRunningRequestMarkerIsIgnored() {
        assertNull(parsePendingChatRequest("""{"status":"completed","request_id":"r","conversation_id":"c"}"""))
    }

    @Test
    fun runningRequestRestoresOnlyItsOwnConversationPrompt() {
        val pending = parsePendingChatRequest(
            """{"status":"running","request_id":"r1","conversation_id":"c1","project_id":"p1","prompt":"继续写","partial_answer":"已完成一半"}"""
        )

        assertEquals("r1", pending?.requestId)
        assertEquals("p1", pending?.projectId)
        assertEquals("已完成一半", pending?.partialAnswer)
        assertEquals("继续写", pending?.restoredPromptFor("c1"))
        assertNull(pending?.restoredPromptFor("c2"))
    }

    @Test
    fun restoredRequestMarkerKeepsItsStableIdButDoesNotReplayPartialAnswer() {
        val pending = parsePendingChatRequest(
            """{"status":"restored","request_id":"r2","conversation_id":"c2","project_id":"p2","prompt":"继续","partial_answer":"旧部分","started_at":1234}"""
        )

        assertEquals("r2", pending?.requestId)
        assertEquals("restored", pending?.status)
        assertEquals("", pending?.partialAnswer)
        assertEquals(1234L, pending?.startedAt)
    }

    @Test
    fun runningRequestWithoutStableIdentifiersIsIgnored() {
        assertNull(parsePendingChatRequest("""{"status":"running","request_id":"","conversation_id":"c"}"""))
        assertNull(parsePendingChatRequest("""{"status":"running","request_id":"r","conversation_id":""}"""))
    }
    @Test
    fun blankProjectIdentifierIsRejected() {
        assertNull(parsePendingChatRequest("""{"status":"running","request_id":"r","conversation_id":"c","project_id":""}"""))
    }

    @Test
    fun interruptedRequestMustMatchItsOriginalProject() {
        val pending = parsePendingChatRequest(
            """{"status":"running","request_id":"r","conversation_id":"c","project_id":"project-a","prompt":"继续"}"""
        )!!
        val original = ConversationRecord("c", "对话", 1L, emptyList(), "project-a")
        val otherProject = original.copy(projectId = "project-b")
        assertEquals(true, pending.matches(original))
        assertEquals(false, pending.matches(otherProject))
    }

    @Test
    fun interruptedRegenerationIsRecoveredAsANewAssistantVersion() {
        val previous = ChatMessage(
            "assistant-1", "assistant", "旧回答",
            versions = listOf("旧回答一", "旧回答"), activeVersion = 1
        )
        val recovered = recoverInterruptedAssistantMessage(
            listOf(ChatMessage("user-1", "user", "问题"), previous),
            requestId = "assistant-1",
            partialAnswer = "新回答的一部分"
        )
        val assistant = recovered.last()
        assertEquals("新回答的一部分\n\n[请求中断，已保留已接收内容]", assistant.content)
        assertEquals(listOf("旧回答一", "旧回答", assistant.content), assistant.versions)
        assertEquals(2, assistant.activeVersion)
    }

    @Test
    fun interruptedRegenerationDoesNotDuplicateAlreadyPersistedResponse() {
        val existing = ChatMessage(
            "assistant-1", "assistant", "已完成的回复",
            versions = listOf("旧回复", "已完成的回复"), activeVersion = 1
        )
        assertEquals(
            listOf(existing),
            recoverInterruptedAssistantMessage(listOf(existing), "assistant-1", "已完成的回复")
        )
    }

    @Test
    fun interruptedNewMessageIsAddedWhenNoAssistantRevisionExists() {
        val recovered = recoverInterruptedAssistantMessage(
            listOf(ChatMessage("user-1", "user", "问题")),
            requestId = "assistant-1",
            partialAnswer = "部分回复"
        )
        assertEquals(2, recovered.size)
        assertEquals("assistant-1", recovered.last().id)
        assertTrue(recovered.last().content.startsWith("部分回复"))
    }

    @Test
    fun interruptedRegenerationWithoutOutputKeepsPreviousAssistantVersion() {
        val previous = ChatMessage(
            "assistant-1", "assistant", "旧回答",
            versions = listOf("旧回答"), activeVersion = 0
        )
        assertEquals(
            listOf(previous),
            recoverInterruptedAssistantMessage(listOf(previous), "assistant-1", "")
        )
    }

    @Test
    fun matchingOlderRevisionDoesNotSuppressNewInterruptedRegeneration() {
        val previous = ChatMessage(
            "assistant-1", "assistant", "当前回答",
            versions = listOf("相同的旧版本", "当前回答"), activeVersion = 1
        )
        val recovered = recoverInterruptedAssistantMessage(
            listOf(previous), "assistant-1", "相同的旧版本"
        )
        assertTrue(recovered.single().content.startsWith("相同的旧版本"))
        assertEquals(2, recovered.single().activeVersion)
        assertEquals(3, recovered.single().versions.size)
    }

}
