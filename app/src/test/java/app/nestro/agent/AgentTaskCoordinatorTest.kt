package app.nestro.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentTaskCoordinatorTest {
    @Test
    fun duplicateTaskIdDoesNotStartASecondExecution() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val traces = InMemoryAgentTaskTraceStore()
        registry.register("test") {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
        }
        val coordinator = AgentTaskCoordinator(states, registry, this, traces)

        coordinator.enqueue("same-task", "test")
        started.await()
        coordinator.enqueue("same-task", "test")
        release.complete(Unit)

        withTimeout(2_000) {
            while (states.get("same-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals(1, calls.get())
        assertTrue(traces.recent("same-task").any { it.event == "completed" })
        coordinator.close()
    }

    @Test
    fun taskIdCannotBeReusedByAnotherHandler() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.register("first") {
            started.complete(Unit)
            release.await()
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        coordinator.enqueue("owned-task", "first")
        started.await()
        val rejected = runCatching { coordinator.enqueue("owned-task", "second") }

        assertTrue(rejected.isFailure)
        assertEquals("first", states.get("owned-task")?.owner)
        release.complete(Unit)
        withTimeout(2_000) {
            while (states.get("owned-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        coordinator.close()
    }

    @Test
    fun pausedTaskCanResumeFromPersistedCheckpoint() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        registry.register("resumable") { context ->
            val attempt = calls.incrementAndGet()
            context.checkpoint("step-$attempt", attempt)
            if (attempt == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        coordinator.enqueue("resume-task", "resumable")
        started.await()
        coordinator.pause("resume-task")
        withTimeout(2_000) {
            while (states.get("resume-task")?.status != AgentTaskStatus.PAUSED) delay(10)
        }
        val paused = states.get("resume-task")
        assertEquals("step-1", paused?.checkpoint)

        coordinator.resume("resume-task")
        withTimeout(2_000) {
            while (states.get("resume-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals(2, calls.get())
        assertEquals("step-2", states.get("resume-task")?.checkpoint)
        coordinator.close()
    }

    @Test
    fun inlineFeatureExecutionUsesSharedQueueStateAndTrace() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val traces = InMemoryAgentTaskTraceStore()
        val coordinator = AgentTaskCoordinator(states, registry, this, traces)

        val result = coordinator.runInline("inline-task", "workflow") { context ->
            context.checkpoint("workflow-step-1", 1)
            "workflow-output"
        }

        assertEquals("workflow-output", result)
        withTimeout(2_000) {
            while (states.get("inline-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals("workflow-step-1", states.get("inline-task")?.checkpoint)
        assertTrue(traces.recent("inline-task").any { it.event == "completed" })
        coordinator.close()
    }

    @Test
    fun recoveryPreservesUserHandoffAndPausesQueuedTasksWithoutHandlers() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        states.put(AgentTaskState("handoff", "workflow", AgentTaskStatus.WAITING_FOR_USER, 2, "step-2", "需要用户确认"))
        states.put(AgentTaskState("orphan", "temporary-handler", AgentTaskStatus.QUEUED, 0, "queued", ""))
        states.put(AgentTaskState("failed", "workflow", AgentTaskStatus.FAILED, 1, "step-1", "模型请求失败"))
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)

        val recovered = coordinator.recoverAfterProcessRestart()

        assertEquals(AgentTaskStatus.WAITING_FOR_USER, recovered.first { it.taskId == "handoff" }.status)
        assertEquals(AgentTaskStatus.PAUSED, states.get("orphan")?.status)
        assertTrue(states.get("orphan")?.lastError?.contains("处理器尚未恢复") == true)
        assertEquals(AgentTaskStatus.FAILED, recovered.first { it.taskId == "failed" }.status)
        coordinator.close()
    }

    @Test
    fun pausingInlineExecutionDoesNotTurnItIntoCancellation() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val coordinator = AgentTaskCoordinator(states, registry, this)
        val started = CompletableDeferred<Unit>()

        val caller = launch {
            runCatching {
                coordinator.runInline("inline-pause", "workflow") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        coordinator.pause("inline-pause")
        caller.join()

        assertEquals(AgentTaskStatus.PAUSED, states.get("inline-pause")?.status)
        coordinator.close()
    }

    @Test
    fun interruptedRunningTaskIsMarkedPausedForManualRecovery() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        states.put(AgentTaskState("interrupted", "test", AgentTaskStatus.RUNNING, 3, "saved", ""))
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)

        val recovered = coordinator.recoverAfterProcessRestart()

        assertNotNull(recovered.firstOrNull())
        assertEquals(AgentTaskStatus.PAUSED, states.get("interrupted")?.status)
        assertEquals("saved", states.get("interrupted")?.checkpoint)
        coordinator.close()
    }
}
