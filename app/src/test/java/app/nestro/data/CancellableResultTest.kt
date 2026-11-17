package app.nestro.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CancellableResultTest {
    @Test
    fun nestedTimeoutBecomesRecoverableFailureWhenCallerRemainsActive() = runBlocking {
        val result = runCatchingCancellable {
            withTimeout(1) { delay(100) }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue(coroutineContext[Job]!!.isActive)
    }

    @Test
    fun callerCancellationIsStillPropagated() = runBlocking {
        val child = launch {
            runCatchingCancellable { awaitCancellation() }
        }
        child.cancelAndJoin()
        assertTrue(child.isCancelled)
    }

    @Test
    fun fatalErrorsAreNotConvertedIntoRecoverableFailures() {
        val fatal = AssertionError("fatal runtime failure")
        val thrown = assertThrows(AssertionError::class.java) {
            runBlocking { runCatchingCancellable { throw fatal } }
        }
        assertTrue(thrown === fatal)
    }

}
