package app.nestro.phone

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A single durable chain prevents two agents from controlling the same device at once. */
object PhoneAgentWorkScheduler {
    const val KEY_TASK_ID = "phone_agent_task_id"
    const val KEY_AFTER_HANDOFF = "phone_agent_after_handoff"
    const val KEY_ALLOW_RETRY = "phone_agent_allow_retry"
    private const val QUEUE_NAME = "nestro-phone-agent-serial-queue"
    private val enqueueMutex = Mutex()

    suspend fun enqueue(
        context: Context,
        taskId: String,
        afterUserHandoff: Boolean = false,
        allowRetryCurrentAction: Boolean = false
    ): Boolean = enqueueMutex.withLock {
        require(taskId.isNotBlank())
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val manager = WorkManager.getInstance(appContext)
            val tag = uniqueName(taskId)
            val existing = manager.getWorkInfosByTag(tag).get()
            if (existing.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED ||
                        it.state == WorkInfo.State.RUNNING
                }
            ) return@withContext false

            appContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .edit().remove(PhoneAgentService.TASK_STOP_PREFIX + taskId).apply()
            val request = OneTimeWorkRequestBuilder<PhoneAgentWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_TASK_ID, taskId)
                        .putBoolean(KEY_AFTER_HANDOFF, afterUserHandoff)
                        .putBoolean(KEY_ALLOW_RETRY, allowRetryCurrentAction)
                        .build()
                )
                .addTag(tag)
                .build()
            manager.enqueueUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
            true
        }
    }

    /** Return the task currently holding the serial device worker, not merely the newest planned task. */
    suspend fun runningTaskId(context: Context): String? = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWork(QUEUE_NAME).get()
            .firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?.tags?.firstOrNull { it.startsWith("nestro-phone-agent-") }
            ?.removePrefix("nestro-phone-agent-")
    }

    /** Cooperative stop is task-scoped; queued tasks become no-ops when they reach the worker. */
    fun requestStop(context: Context, taskId: String) {
        if (taskId.isBlank()) return
        context.applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().putBoolean(PhoneAgentService.TASK_STOP_PREFIX + taskId, true).apply()
    }

    fun cancel(context: Context, taskId: String) = requestStop(context, taskId)

    private fun uniqueName(taskId: String) = "nestro-phone-agent-$taskId"
}
