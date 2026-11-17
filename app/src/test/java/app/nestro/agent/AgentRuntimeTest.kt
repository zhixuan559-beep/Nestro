package app.nestro.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun readOnlyToolRunsAndAdvancesTaskState() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("screen.read", "读取当前页面", AgentToolPermission.READ_ONLY)) {
                AgentToolResult(success = true, output = "页面内容")
            }
        }
        val states = InMemoryAgentTaskStateStore()
        val traces = InMemoryAgentTaskTraceStore()
        val runtime = DefaultAgentRuntime(catalog, DefaultAgentPermissionRouter(), states, traces)

        val result = runtime.execute(AgentToolRequest("task-1", "screen.read"))

        assertTrue(result.success)
        assertEquals("页面内容", result.output)
        assertEquals(1, states.get("task-1")?.currentStep)
        assertEquals(AgentTaskStatus.RUNNING, states.get("task-1")?.status)
        assertTrue(traces.recent("task-1").any { it.event == "tool_succeeded" })
    }

    @Test
    fun externalWriteRequiresUserConfirmationByDefault() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("message.send", "发送消息", AgentToolPermission.EXTERNAL_WRITE)) {
                AgentToolResult(success = true, output = "sent")
            }
        }
        val states = InMemoryAgentTaskStateStore()
        val runtime = DefaultAgentRuntime(catalog, DefaultAgentPermissionRouter(), states)

        val result = runtime.execute(AgentToolRequest("task-2", "message.send"))

        assertFalse(result.success)
        assertTrue(result.requiresUser)
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, states.get("task-2")?.status)
    }

    @Test
    fun missingToolReturnsActionableErrorWithoutExecuting() = runBlocking {
        val runtime = DefaultAgentRuntime(
            InMemoryAgentToolCatalog(),
            DefaultAgentPermissionRouter(),
            InMemoryAgentTaskStateStore()
        )

        val result = runtime.execute(AgentToolRequest("task-3", "missing.tool"))

        assertFalse(result.success)
        assertTrue(result.error.contains("工具不存在"))
    }
}
