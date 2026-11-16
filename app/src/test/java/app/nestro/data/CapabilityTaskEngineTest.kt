package app.nestro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTaskEngineTest {
    @Test fun terminalStatesCannotBeReopened() {
        assertFalse(CapabilityTaskState.canTransition(CapabilityTaskState.SUCCEEDED, CapabilityTaskState.QUEUED))
        assertFalse(CapabilityTaskState.canTransition(CapabilityTaskState.CANCELLED, CapabilityTaskState.RUNNING))
        assertFalse(CapabilityTaskState.canTransition(CapabilityTaskState.FAILED, CapabilityTaskState.RETRY_WAIT))
    }

    @Test fun pausedAndWaitingTasksRequireExplicitResume() {
        assertTrue(CapabilityTaskState.canTransition(CapabilityTaskState.PAUSED, CapabilityTaskState.QUEUED))
        assertTrue(CapabilityTaskState.canTransition(CapabilityTaskState.WAITING_USER, CapabilityTaskState.QUEUED))
        assertTrue(CapabilityTaskState.canTransition(CapabilityTaskState.WAITING_USER, CapabilityTaskState.RUNNING))
    }

    @Test fun runningTasksCanCheckpointRetryOrFinish() {
        assertTrue(CapabilityTaskState.canTransition(CapabilityTaskState.RUNNING, CapabilityTaskState.RETRY_WAIT))
        assertTrue(CapabilityTaskState.canTransition(CapabilityTaskState.RUNNING, CapabilityTaskState.SUCCEEDED))
        assertTrue(CapabilityTaskState.canTransition(CapabilityTaskState.RUNNING, CapabilityTaskState.FAILED))
        assertTrue(CapabilityTaskState.canTransition(CapabilityTaskState.RUNNING, CapabilityTaskState.PAUSED))
    }

    @Test fun queuedTasksCannotSkipTheClaimState() {
        assertFalse(CapabilityTaskState.canTransition(CapabilityTaskState.QUEUED, CapabilityTaskState.SUCCEEDED))
        assertFalse(CapabilityTaskState.canTransition(CapabilityTaskState.QUEUED, CapabilityTaskState.RETRY_WAIT))
    }
}
