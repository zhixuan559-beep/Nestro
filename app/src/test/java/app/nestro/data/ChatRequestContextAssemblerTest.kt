package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestContextAssemblerTest {
    @Test
    fun assemblesBoundedContextFromRecentHistoryAndProjectScopedSources() = kotlinx.coroutines.runBlocking {
        var observedQuery = ""
        var knowledgeCalls = 0
        val source = ConversationRecallSource("old-conversation", "message-1", "旧会话", "user", "历史片段", 1L)
        val assembler = ChatRequestContextAssembler(
            retrieveMemory = { projectId, conversationId, query ->
                assertEquals("project-1", projectId)
                assertEquals("current-conversation", conversationId)
                observedQuery = query
                ConversationRecallResult("历史召回上下文", listOf(source))
            },
            retrieveKnowledge = { _, _ ->
                knowledgeCalls++
                "知识库上下文"
            },
            retrieveUserMemory = { projectId, query ->
                assertEquals("project-1", projectId)
                assertEquals("根据知识库和我的偏好，回忆我们之前讨论的当前问题", query)
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
        assertEquals(0, knowledgeCalls)
        assertTrue(result.systemContext.indexOf("基础上下文") < result.systemContext.indexOf("历史召回上下文"))
        assertFalse(result.systemContext.contains("知识库上下文"))
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

class ChatExecutionModePolicyTest {
    @Test
    fun persistedTeamSelectionDoesNotAuthorizeOrdinaryChat() {
        assertFalse(ChatContextRetrievalPolicy.shouldRunExpertTeam(false, "team-1"))
        assertFalse(ChatContextRetrievalPolicy.shouldRunExpertTeam(false, ""))
    }

    @Test
    fun explicitTaskModeAllowsOnlyAnExplicitlySelectedTeam() {
        assertTrue(ChatContextRetrievalPolicy.shouldRunExpertTeam(true, "team-1"))
        assertFalse(ChatContextRetrievalPolicy.shouldRunExpertTeam(true, ""))
    }
}


class ChatExecutionAuthorizationTest {
    @Test
    fun ordinaryChatCannotPlanPhoneAgentOrRunSavedExpertTeam() {
        val authorization = ChatExecutionAuthorization(false, "saved-team")
        assertFalse(authorization.shouldPlanPhoneAgent())
        assertFalse(authorization.shouldRunExpertTeam())
    }

    @Test
    fun explicitTaskModeCanPlanAndRunOnlyTheCapturedTeam() {
        val authorization = ChatExecutionAuthorization(true, "team-1")
        assertTrue(authorization.shouldPlanPhoneAgent())
        assertTrue(authorization.shouldRunExpertTeam())
        assertFalse(ChatExecutionAuthorization(true, "").shouldRunExpertTeam())
    }
}


class ChatOptionalContextPolicyTest {
    @Test
    fun ordinaryChatDoesNotLoadExpertOrSkillPromptAssets() {
        assertFalse(shouldLoadSelectedExpertContext(taskModeEnabled = false))
    }

    @Test
    fun explicitTaskModeMayLoadSelectedExpertOrSkillPromptAssets() {
        assertTrue(shouldLoadSelectedExpertContext(taskModeEnabled = true))
    }
}


class ChatRequestHistoryBudgetTest {
    @Test
    fun modelHistoryIsBoundedWithoutTruncatingPersistedConversationHistory() {
        val fullHistory = (1..60).map { index ->
            ChatMessage("message-$index", if (index % 2 == 0) "assistant" else "user", "历史消息$index " + "内容".repeat(1000))
        }
        val bounded = ChatRequestPolicy.boundedChatHistory(fullHistory, "系统上下文", contextWindowTokens = 4096, outputReserveTokens = 512)
        assertTrue(bounded.size < fullHistory.size)
        assertTrue(bounded.last().content.contains("历史消息60"))
        assertFalse(bounded.any { it.content.contains("历史消息1 ") })
        assertEquals(60, fullHistory.size)
        assertTrue(bounded.sumOf { ChatRequestPolicy.estimateTokens(it.content) } <= 3584)
    }
}


class ChatRequestContextAssemblerParallelRetrievalTest {
    @Test
    fun independentContextSourcesCanCompleteWithoutSerialWaiting() = kotlinx.coroutines.runBlocking {
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val allStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        suspend fun markStarted(): String {
            if (started.incrementAndGet() == 2) allStarted.complete(Unit)
            kotlinx.coroutines.withTimeout(1_000) { allStarted.await() }
            return "并行来源"
        }
        var knowledgeCalls = 0
        val assembler = ChatRequestContextAssembler(
            retrieveMemory = { _, _, _ -> markStarted().let { ConversationRecallResult(it, emptyList()) } },
            retrieveKnowledge = { _, _ -> knowledgeCalls++; "不应预取" },
            retrieveUserMemory = { _, _ -> markStarted() }
        )
        val result = assembler.assemble(
            projectId = "p",
            conversationId = "c",
            prompt = "结合知识库、历史对话和我的偏好分析",
            requestHistory = emptyList(),
            baseSystemContext = "base"
        )
        assertEquals(2, started.get())
        assertEquals(0, knowledgeCalls)
        assertTrue(result.systemContext.contains("并行来源"))
    }
}
