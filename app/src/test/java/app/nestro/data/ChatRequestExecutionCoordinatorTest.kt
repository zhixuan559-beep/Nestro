package app.nestro.data

import app.nestro.agent.AgentToolResult
import app.nestro.model.ChatPartialStateStore
import app.nestro.model.ChatStreamingCoordinator
import app.nestro.model.ChatStreamingTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestExecutionCoordinatorTest {
    private fun coordinator(
        stream: suspend (String, suspend (String) -> Unit) -> String
    ): ChatRequestExecutionCoordinator {
        val assembler = ChatRequestContextAssembler(
            retrieveMemory = { _, _, _ -> ConversationRecallResult("", emptyList()) },
            retrieveKnowledge = { _, _ -> "" },
            retrieveUserMemory = { _, _ -> "" }
        )
        val streaming = ChatStreamingCoordinator(
            transport = ChatStreamingTransport { _, _, systemPrompt, _, _, _, _, _, _, onChunk ->
                stream(systemPrompt, onChunk)
            },
            partialStateStore = ChatPartialStateStore { _, _, _, _, _, _ -> }
        )
        return ChatRequestExecutionCoordinator(assembler, streaming)
    }

    private fun request(taskMode: Boolean = false, shouldStop: () -> Boolean = { false }) = ChatRequestExecutionRequest(
        requestId = "request-1",
        conversationId = "conversation-1",
        projectId = "project-1",
        prompt = "这是一个需要回答的问题",
        startedAt = 100L,
        history = listOf(ChatMessage("user-1", "user", "这是一个需要回答的问题")),
        modelConfigs = emptyList(),
        baseSystemContext = "base-context",
        taskModeEnabled = taskMode,
        temperature = null,
        maxTokens = null,
        previousAssistant = null,
        shouldStop = shouldStop
    )

    @Test
    fun ordinaryChatDoesNotPreemptivelyRunTeamOrPhoneAgent() = runBlocking {
        val order = mutableListOf<String>()
        var observedSystemPrompt = ""
        val coordinator = coordinator { systemPrompt, onChunk ->
            observedSystemPrompt = systemPrompt
            order += "stream"
            onChunk("partial")
            "complete"
        }
        val result = coordinator.execute(
            request = request(),
            onUsage = { _, _, _ -> },
            onProviderUsage = { _, _, _ -> },
            planPhoneAgent = { order += "plan"; AgentToolResult(true, output = "planned") },
            runExpertTeam = { context, _ ->
                assertEquals("base-context", context)
                order += "team"
                "team-context"
            },
            onPartialAnswer = { order += "partial:$it" }
        )
        assertEquals(listOf("stream", "partial:partial"), order)
        assertTrue(observedSystemPrompt.contains("base-context"))
        assertTrue(!observedSystemPrompt.contains("team-context"))
        assertEquals("complete", result.finalization.finalText)
        assertEquals(ChatResponseStatus.SUCCESS, result.finalization.status)
    }

    @Test
    fun taskModeDoesNotPreemptivelyPlanBeforeModelDecision() = runBlocking {
        var planCalls = 0
        val coordinator = coordinator { _, _ -> "reply" }
        coordinator.execute(
            request = request(taskMode = false),
            onUsage = { _, _, _ -> },
            onProviderUsage = { _, _, _ -> },
            planPhoneAgent = { planCalls++; AgentToolResult(true, output = "planned") },
            onPartialAnswer = { }
        )
        assertEquals(0, planCalls)

        coordinator.execute(
            request = request(taskMode = true),
            onUsage = { _, _, _ -> },
            onProviderUsage = { _, _, _ -> },
            planPhoneAgent = { planCalls++; "planned" },
            onPartialAnswer = { }
        )
        assertEquals(0, planCalls)
    }

    @Test
    fun preservesPartialOutputWhenStreamingFails() = runBlocking {
        val coordinator = coordinator { _, onChunk ->
            onChunk("partial answer")
            error("network disconnected")
        }
        val result = coordinator.execute(
            request = request(),
            onUsage = { _, _, _ -> },
            onProviderUsage = { _, _, _ -> },
            onPartialAnswer = { }
        )
        assertEquals("partial answer", result.finalization.finalText)
        assertEquals(ChatResponseStatus.FAILURE, result.finalization.status)
    }

    @Test
    fun propagatesParentCancellation() = runBlocking {
        val coordinator = coordinator { _, _ -> throw CancellationException("cancelled") }
        val result = runCatching {
            coordinator.execute(
                request = request(),
                onUsage = { _, _, _ -> },
                onProviderUsage = { _, _, _ -> },
                onPartialAnswer = { }
            )
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
    @Test
    fun phoneAgentPlanningCallbackIsDeferredUntilModelSelectsItsTool() = runBlocking {
        var planningError = ""
        val coordinator = coordinator { _, _ -> "reply" }
        val result = coordinator.execute(
            request = request(taskMode = true),
            onUsage = { _, _, _ -> },
            onProviderUsage = { _, _, _ -> },
            planPhoneAgent = { error("scheduler unavailable") },
            onTaskPlanningFailure = { planningError = it.message.orEmpty() },
            onPartialAnswer = { }
        )
        assertEquals("", planningError)
        assertEquals(null, result.taskPlanningFailure)
        assertEquals("reply", result.finalization.finalText)
        assertEquals(ChatResponseStatus.SUCCESS, result.finalization.status)
    }

    @Test
    fun stopRequestedBeforeStreamingPreventsTransportCall() = runBlocking {
        var streamCalls = 0
        val coordinator = coordinator { _, _ ->
            streamCalls++
            "should not run"
        }
        val result = coordinator.execute(
            request = request(shouldStop = { true }),
            onUsage = { _, _, _ -> },
            onProviderUsage = { _, _, _ -> },
            onPartialAnswer = { }
        )
        assertEquals(0, streamCalls)
        assertEquals(ChatResponseStatus.STOPPED, result.finalization.status)
        assertEquals("已停止生成。", result.finalization.finalText)
    }

}
