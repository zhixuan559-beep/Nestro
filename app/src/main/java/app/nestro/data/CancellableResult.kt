package app.nestro.data

import kotlinx.coroutines.CancellationException

/**
 * Captures ordinary operation failures without converting coroutine cancellation
 * into a recoverable Result. Callers can safely use this around suspending work.
 */
internal suspend fun <T> runCatchingCancellable(
    block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
