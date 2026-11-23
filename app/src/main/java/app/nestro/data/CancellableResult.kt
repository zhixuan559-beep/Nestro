package app.nestro.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Captures ordinary failures and child-operation timeouts without swallowing
 * cancellation of the caller's coroutine.
 */
internal suspend fun <T> runCatchingCancellable(
    block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (timeout: TimeoutCancellationException) {
    // A nested withTimeout may expire while the caller remains active.
    // If the caller itself was cancelled, ensureActive rethrows that cancellation.
    currentCoroutineContext().ensureActive()
    Result.failure(timeout)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
