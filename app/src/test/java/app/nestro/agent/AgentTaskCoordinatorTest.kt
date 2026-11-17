package app.nestro.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
    }
}
