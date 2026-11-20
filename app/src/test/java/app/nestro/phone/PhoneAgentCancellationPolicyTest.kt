package app.nestro.phone

import app.nestro.data.PhoneAgentTaskStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneAgentCancellationPolicyTest {
    @Test
    fun persistedCancelledStatusIsTerminal() {
        assertTrue(
            PhoneAgentCancellationPolicy.isCancellationRequested(
                PhoneAgentTaskStore.STATUS_CANCELLED,
                false
            )
        )
    }

    @Test
    fun explicitCancelRequestStopsQueuedOrRunningTask() {
        assertTrue(PhoneAgentCancellationPolicy.isCancellationRequested(PhoneAgentTaskStore.STATUS_PLANNED, true))
        assertTrue(PhoneAgentCancellationPolicy.isCancellationRequested(PhoneAgentTaskStore.STATUS_RUNNING, true))
    }

    @Test
    fun stopRequestKeysAreIsolatedBetweenTasks() {
        assertNotEquals(
            PhoneAgentWorkScheduler.stopKey("task-a"),
            PhoneAgentWorkScheduler.stopKey("task-b")
        )
        assertTrue(PhoneAgentWorkScheduler.stopKey("task-a").contains("task-a"))
    }

    @Test
    fun activeTaskWithoutCancelRequestMayContinue() {
        assertFalse(PhoneAgentCancellationPolicy.isCancellationRequested(PhoneAgentTaskStore.STATUS_RUNNING, false))
        assertFalse(PhoneAgentCancellationPolicy.isCancellationRequested(PhoneAgentTaskStore.STATUS_WAITING_USER, false))
    }
}
