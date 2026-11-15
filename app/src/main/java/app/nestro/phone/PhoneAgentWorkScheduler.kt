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

    fun enqueue(context: Context, taskId: String, afterUserHandoff: Boolean = false, allowRetryCurrentAction: Boolean = false) {
        require(taskId.isNotBlank())
        context.applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().remove("phone_task_stop_requested").apply()
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
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName(taskId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, taskId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(taskId))
    }

    private fun uniqueName(taskId: String) = "nestro-phone-agent-$taskId"
}
