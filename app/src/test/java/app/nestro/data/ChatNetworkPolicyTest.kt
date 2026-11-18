package app.nestro.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ChatNetworkPolicyTest {
    @Test fun retryUsesBoundedBackoffAndStopsAfterMaxAttempts() {
        assertEquals(800L, ChatNetworkPolicy.retryPlan(IOException("timeout"), 0)?.delayMs)
        assertEquals(1600L, ChatNetworkPolicy.retryPlan(IOException("timeout"), 1)?.delayMs)
        assertNull(ChatNetworkPolicy.retryPlan(IOException("timeout"), 2))
    }

    @Test fun cancellationNeverRetries() {
        assertNull(ChatNetworkPolicy.retryPlan(CancellationException("cancel"), 0))
        assertEquals("cancelled", ChatNetworkPolicy.classify(CancellationException("cancel")))
    }

    @Test fun classifiesHttpFailures() {
        assertEquals("auth", ChatNetworkPolicy.classify(IllegalStateException("HTTP 401")))
        assertEquals("rate_limited", ChatNetworkPolicy.classify(IllegalStateException("HTTP 429")))
        assertEquals("server", ChatNetworkPolicy.classify(IllegalStateException("HTTP 503")))
    }
}
