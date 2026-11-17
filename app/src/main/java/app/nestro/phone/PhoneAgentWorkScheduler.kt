package app.nestro.phone

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object PhoneAgentWorkScheduler {
    const val KEY_TASK_ID = "phone_agent_task_id"
    const val KEY_AFTER_HANDOFF = "phone_agent_after_handoff"
    const val KEY_ALLOW_RETRY = "phone_agent_allow_retry"
    private const val QUEUE_NAME = "nestro-phone-agent-queue"

    fun enqueue(context: Context, taskId: String, afterUserHandoff: Boolean = false, allowRetryCurrentAction: Boolean = false) {
        require(taskId.isNotBlank())
        val preferences = context.applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val pendingKey = pendingKey(taskId)
        if (preferences.getBoolean(pendingKey, false)) return
        preferences.edit()
            .putBoolean(pendingKey, true)
            .putBoolean(afterHandoffKey(taskId), afterUserHandoff)
            .putBoolean(allowRetryKey(taskId), allowRetryCurrentAction)
            .apply()
        val request = OneTimeWorkRequestBuilder<PhoneAgentWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_TASK_ID, taskId)
                    .putBoolean(KEY_AFTER_HANDOFF, afterUserHandoff)
                    .putBoolean(KEY_ALLOW_RETRY, allowRetryCurrentAction)
                    .build()
            )
            .addTag(uniqueName(taskId))
            .build()
        try {
            WorkManager.getInstance(context.applicationContext)
                .beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .enqueue()
        } catch (error: Throwable) {
            preferences.edit().remove(pendingKey).remove(afterHandoffKey(taskId)).remove(allowRetryKey(taskId)).apply()
            throw error
        }
    }

    fun cancel(context: Context, taskId: String, stopCurrent: Boolean = false) {
        val preferences = context.applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        // Persist cancellation even before this task reaches the head of the shared WorkManager chain.
        // Do not cancel the tagged WorkRequest: WorkManager may cancel dependent requests in the
        // same unique-work chain as well. The Room terminal state + coordinator cancellation stop
        // this task; its worker returns success so later queued tasks can continue.
        preferences.edit().putBoolean(cancelKey(taskId), true).apply()
        if (stopCurrent) PhoneAgentService.activeService?.requestTaskStop()
    }

    internal fun cancelKey(taskId: String) = "phone_agent_cancel_requested_$taskId"
    internal fun afterHandoffKey(taskId: String) = "phone_agent_after_handoff_$taskId"
    internal fun allowRetryKey(taskId: String) = "phone_agent_allow_retry_$taskId"
    internal fun pendingKey(taskId: String) = "phone_agent_work_pending_$taskId"
    private fun uniqueName(taskId: String) = "nestro-phone-agent-$taskId"
}
