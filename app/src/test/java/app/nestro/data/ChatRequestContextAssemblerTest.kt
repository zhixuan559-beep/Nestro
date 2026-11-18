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
                assertEquals("根据知识库和我的偏好，回忆我们之前讨论的当前问题", query)
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
            prompt = "根据知识库和我的偏好，回忆我们之前讨论的当前问题",
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


class ChatRequestContextAssemblerLightweightTest {
    @Test
    fun ordinaryChatSkipsAllOptionalRetrievalAndPhoneAgentContext() = kotlinx.coroutines.runBlocking {
        var retrievalCalls = 0
        val assembler = ChatRequestContextAssembler(
            retrieveMemory = { _, _, _ -> retrievalCalls++; ConversationRecallResult("旧对话", emptyList()) },
            retrieveKnowledge = { _, _ -> retrievalCalls++; "知识库" },
            retrieveUserMemory = { _, _ -> retrievalCalls++; "个人记忆" }
        )
        val result = assembler.assemble(
            projectId = "p", conversationId = "c", prompt = "帮我搜索咖啡价格",
            requestHistory = emptyList(), baseSystemContext = "轻量基础上下文", taskModeEnabled = false
        )
        assertEquals(0, retrievalCalls)
        assertEquals(0, result.recalledCount)
        assertTrue(result.recallSources.isEmpty())
        assertEquals("轻量基础上下文", result.systemContext)
    }

    @Test
    fun explicitTaskModeAddsPhoneAgentInstructionsButOrdinaryChatDoesNot() = kotlinx.coroutines.runBlocking {
        val assembler = ChatRequestContextAssembler(
            retrieveMemory = { _, _, _ -> ConversationRecallResult("", emptyList()) },
            retrieveKnowledge = { _, _ -> "" },
            retrieveUserMemory = { _, _ -> "" }
        )
        val prompt = "帮我预约明天的餐厅"
        val ordinary = assembler.assemble("p", "c", prompt, emptyList(), "", taskModeEnabled = false)
        val task = assembler.assemble("p", "c", prompt, emptyList(), "", taskModeEnabled = true)
        assertFalse(ordinary.systemContext.contains("Phone Agent 目标执行约束"))
        assertTrue(task.systemContext.contains("Phone Agent 目标执行约束"))
    }
}