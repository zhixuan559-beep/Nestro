package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolPermission
import app.nestro.agent.AgentToolSpec
import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.InMemoryAgentToolCatalog
import app.nestro.agent.AgentTaskHandlerRegistry
import app.nestro.agent.AgentTaskStatus
import app.nestro.agent.InMemoryAgentTaskStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowExecutionCoordinatorTest {
    @Test
    fun executesWorkflowAndCompletesSharedAgentTaskLifecycle() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = WorkflowExecutionCoordinator(coordinator)
        val callbacks = mutableListOf<String>()
        try {
            val workflow = WorkflowDefinition(
                id = "workflow-1",
                name = "测试工作流",
                description = "单步测试",
                steps = listOf(WorkflowStep(title = "分析", instruction = "分析用户输入"))
            )
            val result = service.execute(
                workflow = workflow,
                input = "测试目标",
                taskId = "workflow-lifecycle-test",
                invokeModel = { _, prompt -> "已处理：$prompt" },
                onStep = { _, step -> callbacks += step.title }
            )
            assertEquals(1, result.completedSteps)
            assertTrue(result.output.contains("已处理"))
            assertEquals(listOf("分析"), callbacks)
            withTimeout(2_000) {
                while (states.get("workflow-lifecycle-test")?.status != AgentTaskStatus.COMPLETED) delay(10)
            }
            assertEquals(AgentTaskStatus.COMPLETED, states.get("workflow-lifecycle-test")?.status)
        } finally {
            coordinator.close()
        }
    }
    @Test
    fun workflowStepReceivesOnlyItsDeclaredToolsAndLeaseClosesAfterInvocation() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = WorkflowExecutionCoordinator(coordinator)
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("knowledge.search", "搜索知识库", AgentToolPermission.READ_ONLY)) {
                app.nestro.agent.AgentToolResult(true, "结果")
            }
            register(AgentToolSpec("message.send", "发送消息", AgentToolPermission.EXTERNAL_WRITE)) {
                app.nestro.agent.AgentToolResult(true, "已发送")
            }
        }
        var captured: AgentToolDiscovery? = null
        AgentCapabilityRegistry.register("workflow", "step-research", setOf("knowledge.search"))
        try {
            val workflow = WorkflowDefinition(
                id = "workflow-tools",
                name = "工具范围测试",
                description = "按步骤加载工具",
                steps = listOf(WorkflowStep(id = "step-research", title = "研究", instruction = "研究主题"))
            )
            val result = service.execute(
                workflow = workflow,
                input = "测试目标",
                taskId = "workflow-tool-scope",
                invokeModel = { _, _ -> error("应使用工具感知调用") },
                toolCatalog = catalog,
                invokeModelWithTools = { _, prompt, access ->
                    captured = access
                    assertEquals(listOf("knowledge.search"), access?.list()?.map { it.id })
                    "已研究：$prompt"
                }
            )
            assertEquals(1, result.completedSteps)
            assertEquals(null, captured?.find("knowledge.search"))
            assertEquals(null, captured?.find("message.send"))
        } finally {
            AgentCapabilityRegistry.unregister("workflow", "step-research")
            coordinator.close()
        }
    }

}
