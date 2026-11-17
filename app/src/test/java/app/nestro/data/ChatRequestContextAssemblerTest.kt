package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestContextAssemblerTest {
    @Test
    fun assemblesBoundedContextFromRecentHistoryAndProjectScopedSources() = kotlinx.coroutines.runBlocking {
        var observedQuery = ""
        val source = ConversationRecallSource("old-conversation", "message-1", "旧会话", "user", "历史片段", 1L)
        val assembler = ChatRequestContextAssembler(
            retrieveMemory = { projectId, conversationId, query ->
                assertEquals("project-1", projectId)
                assertEquals("current-conversation", conversationId)
                observedQuery = query
                ConversationRecallResult("历史召回上下文", listOf(source))
            },
            retrieveKnowledge = { projectId, query ->
                assertEquals("project-1", projectId)
                assertEquals("当前问题", query)
                "知识库上下文"
            },
            retrieveUserMemory = { projectId, query ->
                assertEquals("project-1", projectId)
                assertEquals("当前问题", query)
                "用户记忆上下文"
            }
        )
        val history = (1..10).map { ChatMessage("m$it", "user", "历史消息$it") }

        val result = assembler.assemble(
            projectId = "project-1",
            conversationId = "current-conversation",
            prompt = "当前问题",
            requestHistory = history,
            baseSystemContext = "基础上下文"
        )

        assertTrue(observedQuery.contains("历史消息3"))
        assertTrue(observedQuery.contains("历史消息10"))
        assertFalse(observedQuery.lines().contains("历史消息1"))
        assertEquals(1, result.recalledCount)
        assertEquals(listOf(source), result.recallSources)
        assertTrue(result.systemContext.indexOf("基础上下文") < result.systemContext.indexOf("历史召回上下文"))
        assertTrue(result.systemContext.contains("知识库上下文"))
        assertTrue(result.systemContext.contains("用户记忆上下文"))
        assertTrue(result.systemContext.length <= 16_000)
    }
}
