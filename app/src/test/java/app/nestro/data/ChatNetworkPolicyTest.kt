package app.nestro.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNetworkPolicyTest {
    @Test
    fun usesBoundedTimeoutDefaults() {
        val policy = ChatNetworkPolicy.timeoutPolicy()
        assertTrue(policy.connectMs in 1_000..120_000)
        assertTrue(policy.readMs in 1_000..300_000)
        assertTrue(policy.callMs >= policy.readMs)
    }

    @Test
    fun retryPlanUsesBoundedExponentialBackoff() {
        assertEquals(ChatRetryPlan(1, 2, 800), ChatNetworkPolicy.retryPlan(IOException("connection reset"), 0))
        assertEquals(ChatRetryPlan(2, 2, 1_600), ChatNetworkPolicy.retryPlan(IOException("connection reset"), 1))
        assertNull(ChatNetworkPolicy.retryPlan(IOException("connection reset"), 2))
        assertNull(ChatNetworkPolicy.retryPlan(CancellationException("cancelled"), 0))
    }

    @Test
    fun classifiesNetworkFailuresWithoutRetryingPermanentErrors() {
        assertEquals("auth", ChatNetworkPolicy.classify(IllegalStateException("HTTP 401")))
        assertEquals("rate_limited", ChatNetworkPolicy.classify(IllegalStateException("HTTP 429")))
        assertEquals("server", ChatNetworkPolicy.classify(IllegalStateException("HTTP 503")))
        assertEquals("transport", ChatNetworkPolicy.classify(IOException("socket timeout")))
        assertEquals("cancelled", ChatNetworkPolicy.classify(CancellationException("cancelled")))
        assertEquals("unknown", ChatNetworkPolicy.classify(IllegalStateException("bad request")))
    }
}
