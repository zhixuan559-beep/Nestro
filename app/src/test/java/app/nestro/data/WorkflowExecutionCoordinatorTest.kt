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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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

    @Test
    fun resumesFromPersistedStepWithoutRepeatingCompletedWork() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val workflows = WorkflowExecutionCoordinator(tasks)
        val workflow = WorkflowDefinition(
            id = "workflow-resume-test",
            name = "断点恢复",
            description = "",
            steps = listOf(
                WorkflowStep(id = "step-a", title = "步骤 A", instruction = "完成 A"),
                WorkflowStep(id = "step-b", title = "步骤 B", instruction = "完成 B")
            )
        )
        val stepBStarted = CompletableDeferred<Unit>()
        var firstPassCalls = 0
        val interrupted = launch {
            runCatching {
                workflows.execute(
                    workflow = workflow,
                    input = "完成测试任务",
                    taskId = "workflow-resume-test-task",
                    invokeModel = { step, _ ->
                        firstPassCalls++
                        if (step.id == "step-a") "A result"
                        else {
                            stepBStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                )
            }
        }
        withTimeout(2_000) { stepBStarted.await() }
        tasks.pause("workflow-resume-test-task")
        interrupted.join()
        assertEquals(AgentTaskStatus.PAUSED, tasks.state("workflow-resume-test-task")?.status)
        assertEquals(2, firstPassCalls)
        assertTrue(runCatching {
            workflows.execute(
                workflow = workflow.copy(steps = workflow.steps.mapIndexed { index, step ->
                    if (index == 1) step.copy(instruction = "被修改的 B") else step
                }),
                input = "完成测试任务",
                taskId = "workflow-resume-test-task",
                invokeModel = { _, _ -> "不应执行" }
            )
        }.isFailure)
        assertTrue(runCatching {
            workflows.execute(
                workflow = workflow,
                input = "不同的用户目标",
                taskId = "workflow-resume-test-task",
                invokeModel = { _, _ -> "不应执行" }
            )
        }.isFailure)

        var resumedCalls = 0
        val result = workflows.execute(
            workflow = workflow,
            input = "完成测试任务",
            taskId = "workflow-resume-test-task",
            invokeModel = { step, prompt ->
                resumedCalls++
                assertEquals("step-b", step.id)
                assertTrue(prompt.contains("A result"))
                "B result"
            }
        )
        assertEquals("B result", result.output)
        assertEquals(1, resumedCalls)
        assertEquals(AgentTaskStatus.COMPLETED, tasks.state("workflow-resume-test-task")?.status)
        tasks.close()
    }


    @Test
    fun approvalWaitIsPersistedAndApprovedWorkflowResumesSharedTask() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val workflows = WorkflowExecutionCoordinator(tasks)
        val approvalRequested = CompletableDeferred<Unit>()
        val decision = CompletableDeferred<Boolean>()
        val workflow = WorkflowDefinition(
            id = "workflow-approval-wait",
            name = "审批恢复",
            description = "",
            steps = listOf(
                WorkflowStep(
                    id = "approval-step",
                    title = "外部写入",
                    instruction = "执行外部写入",
                    approvalRequired = true
                )
            )
        )
        try {
            val execution = launch {
                workflows.execute(
                    workflow = workflow,
                    input = "执行审批测试",
                    taskId = "workflow-approval-wait-task",
                    invokeModel = { _, _ -> "已执行" },
                    requestApproval = { _, _ ->
                        approvalRequested.complete(Unit)
                        decision.await()
                    }
                )
            }
            withTimeout(2_000) { approvalRequested.await() }
            withTimeout(2_000) {
                while (tasks.state("workflow-approval-wait-task")?.status != AgentTaskStatus.WAITING_FOR_USER) {
                    delay(10)
                }
            }
            assertEquals(AgentTaskStatus.WAITING_FOR_USER, tasks.state("workflow-approval-wait-task")?.status)
            decision.complete(true)
            withTimeout(2_000) { execution.join() }
            assertEquals(AgentTaskStatus.COMPLETED, tasks.state("workflow-approval-wait-task")?.status)
        } finally {
            tasks.close()
        }
    }

    @Test
    fun rejectedApprovalEndsWorkflowTaskAsFailed() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val workflows = WorkflowExecutionCoordinator(tasks)
        val workflow = WorkflowDefinition(
            id = "workflow-approval-rejected",
            name = "拒绝审批",
            description = "",
            steps = listOf(
                WorkflowStep(
                    id = "approval-step",
                    title = "外部写入",
                    instruction = "执行外部写入",
                    approvalRequired = true
                )
            )
        )
        try {
            assertTrue(runCatching {
                workflows.execute(
                    workflow = workflow,
                    input = "执行拒绝测试",
                    taskId = "workflow-approval-rejected-task",
                    invokeModel = { _, _ -> "不应执行" },
                    requestApproval = { _, _ -> false }
                )
            }.isFailure)
            withTimeout(2_000) {
                while (tasks.state("workflow-approval-rejected-task")?.status != AgentTaskStatus.FAILED) {
                    delay(10)
                }
            }
            assertEquals(AgentTaskStatus.FAILED, tasks.state("workflow-approval-rejected-task")?.status)
        } finally {
            tasks.close()
        }
    }


    @Test
    fun approvalWaitIsPersistedAndApprovalResumesUnifiedTaskLifecycle() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val workflows = WorkflowExecutionCoordinator(tasks)
        val approval = CompletableDeferred<Boolean>()
        val workflow = WorkflowDefinition(
            id = "workflow-approval-state",
            name = "审批状态",
            description = "",
            steps = listOf(
                WorkflowStep(id = "approval-step", title = "外部操作", instruction = "执行操作", approvalRequired = true)
            )
        )
        val taskId = "workflow-approval-state-task"
        try {
            val execution = launch {
                workflows.execute(
                    workflow = workflow,
                    input = "执行受控操作",
                    taskId = taskId,
                    invokeModel = { _, _ -> "执行完成" },
                    requestApproval = { _, _ -> approval.await() }
                )
            }
            withTimeout(2_000) {
                while (states.get(taskId)?.status != AgentTaskStatus.WAITING_FOR_USER) delay(10)
            }
            assertEquals(AgentTaskStatus.WAITING_FOR_USER, states.get(taskId)?.status)
            approval.complete(true)
            execution.join()
            assertEquals(AgentTaskStatus.COMPLETED, states.get(taskId)?.status)

            val rejectedTaskId = "workflow-approval-rejected"
            assertTrue(runCatching {
                workflows.execute(
                    workflow = workflow,
                    input = "执行受控操作",
                    taskId = rejectedTaskId,
                    invokeModel = { _, _ -> error("审批拒绝后不得执行") },
                    requestApproval = { _, _ -> false }
                )
            }.isFailure)
            assertEquals(AgentTaskStatus.FAILED, states.get(rejectedTaskId)?.status)
        } finally {
            tasks.close()
        }
    }

}
