package app.nestro.phone

import app.nestro.data.PhoneAgentTaskStore

/** Single cancellation rule shared by UI scheduling, the worker and the executor. */
object PhoneAgentCancellationPolicy {
    fun isCancellationRequested(taskStatus: String, cancelRequested: Boolean): Boolean =
        cancelRequested || taskStatus == PhoneAgentTaskStore.STATUS_CANCELLED
}
