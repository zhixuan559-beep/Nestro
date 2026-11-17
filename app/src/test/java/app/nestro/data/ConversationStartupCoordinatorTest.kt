package app.nestro.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ConversationStartupCoordinatorTest {
    @Test
    fun cancellableResultReturnsOrdinaryFailures() = runBlocking {
        val result = runCatchingCancellable<String> { error("storage unavailable") }

        assertFalse(result.isSuccess)
        assertEquals("storage unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellableResultRethrowsCoroutineCancellation() = runBlocking {
        val cancelled = CancellationException("startup cancelled")
        try {
            runCatchingCancellable<String> { throw cancelled }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
