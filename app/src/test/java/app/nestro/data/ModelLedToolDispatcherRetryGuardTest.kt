package app.nestro.data

import app.nestro.agent.AgentTaskRetryPolicy
import app.nestro.agent.NonRetryableAgentTaskException
import app.nestro.agent.RetryableAgentTaskException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLedToolDispatcherRetryGuardTest {
    @Test
    fun transientNetworkFailureCanRetryBeforeAnyToolStarts() {
        val error = SocketTimeoutException("model request timed out")
        assertSame(error, guardRetryAfterToolExecution(error, toolCallsExecuted = 0))
        assertTrue(AgentTaskRetryPolicy.isRetryable(error))
    }

    @Test
    fun uncertainFailureAfterToolStartCannotRetry() {
        val error = SocketTimeoutException("connection dropped after tool call")
        val guarded = guardRetryAfterToolExecution(error, toolCallsExecuted = 1)
        assertTrue(guarded is NonRetryableAgentTaskException)
        assertFalse(AgentTaskRetryPolicy.isRetryable(guarded))
    }

    @Test
    fun transientHttpStatusesAreRetryableButPermanentClientErrorsAreNot() {
        listOf(408, 425, 429, 500, 502, 503, 504, 529).forEach { status ->
            val failure = AgentTaskRetryPolicy.exceptionForHttpStatus(status, "HTTP $status")
            assertTrue("HTTP $status should be retryable", failure is RetryableAgentTaskException)
            assertTrue(AgentTaskRetryPolicy.isRetryable(failure))
        }
        listOf(400, 401, 403, 404, 422).forEach { status ->
            val failure = AgentTaskRetryPolicy.exceptionForHttpStatus(status, "HTTP $status")
            assertFalse("HTTP $status must not be retried", AgentTaskRetryPolicy.isRetryable(failure))
        }
    }

    @Test
    fun existingNonRetryableGuardIsPreserved() {
        val error = NonRetryableAgentTaskException("side effect uncertain", SocketTimeoutException())
        assertSame(error, guardRetryAfterToolExecution(error, toolCallsExecuted = 2))
        assertFalse(AgentTaskRetryPolicy.isRetryable(error))
    }
}
