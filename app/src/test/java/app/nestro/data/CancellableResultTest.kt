package app.nestro.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResultTest {
    @Test
    fun nestedTimeoutBecomesRecoverableFailureWhenCallerRemainsActive() = runBlocking {
        val result = runCatchingCancellable {
            withTimeout(1) { delay(100) }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertFalse(!coroutineContext[ kotlinx.coroutines.Job ]!!.isActive)
    }

    @Test
    fun callerCancellationIsStillPropagated() = runBlocking {
        val child = launch {
            runCatchingCancellable { awaitCancellation() }
        }
        child.cancelAndJoin()
        assertTrue(child.isCancelled)
    }
}
