package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
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
}
