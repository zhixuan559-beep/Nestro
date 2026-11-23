package app.nestro.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentPendingWorkPolicyTest {
    @Test fun liveWorkRequestKeepsPendingMarker() {
        assertFalse(PhoneAgentPendingWorkPolicy.shouldReconcile(1_000L, 120_000L, true))
    }
    @Test fun recentMarkerIsNotReconciledToAvoidEnqueueRace() {
        assertFalse(PhoneAgentPendingWorkPolicy.shouldReconcile(100_000L, 110_000L, false))
    }
    @Test fun staleMarkerWithoutWorkCanBeReconciled() {
        assertTrue(PhoneAgentPendingWorkPolicy.shouldReconcile(1_000L, 40_000L, false))
    }
    @Test fun clockRollbackDoesNotTreatMarkerAsStale() {
        assertFalse(PhoneAgentPendingWorkPolicy.shouldReconcile(40_000L, 30_000L, false))
    }
}
