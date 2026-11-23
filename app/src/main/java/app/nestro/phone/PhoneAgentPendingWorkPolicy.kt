package app.nestro.phone

/** Crash-window reconciliation policy for Phone Agent pending markers. */
object PhoneAgentPendingWorkPolicy {
    const val MIN_PENDING_AGE_MILLIS = 30_000L

    fun shouldReconcile(pendingAt: Long, now: Long, workExists: Boolean): Boolean =
        !workExists && pendingAt > 0L && now >= pendingAt && now - pendingAt >= MIN_PENDING_AGE_MILLIS
}
