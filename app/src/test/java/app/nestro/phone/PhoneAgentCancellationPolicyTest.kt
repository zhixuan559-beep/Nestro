package app.nestro.phone

import app.nestro.data.PhoneAgentTaskStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun activeTaskWithoutCancelRequestMayContinue() {
        assertFalse(PhoneAgentCancellationPolicy.isCancellationRequested(PhoneAgentTaskStore.STATUS_RUNNING, false))
        assertFalse(PhoneAgentCancellationPolicy.isCancellationRequested(PhoneAgentTaskStore.STATUS_WAITING_USER, false))
    }
}
