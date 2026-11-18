package app.nestro.phone

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PhoneAgentWorkScheduler {
    const val KEY_TASK_ID = "phone_agent_task_id"
    const val KEY_AFTER_HANDOFF = "phone_agent_after_handoff"
    const val KEY_ALLOW_RETRY = "phone_agent_allow_retry"
    private const val QUEUE_NAME = "nestro-phone-agent-queue"

    @Synchronized
    fun enqueue(context: Context, taskId: String, afterUserHandoff: Boolean = false, allowRetryCurrentAction: Boolean = false, allowExistingMarker: Boolean = false) {
        require(taskId.isNotBlank())
        val preferences = context.applicationContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val pendingKey = pendingKey(taskId)
        if (preferences.getBoolean(pendingKey, false) && !allowExistingMarker) return
        val markerSaved = preferences.edit()
            .putBoolean(pendingKey, true)
            .putLong(pendingAtKey(taskId), System.currentTimeMillis())
            .putBoolean(afterHandoffKey(taskId), afterUserHandoff)
            .putBoolean(allowRetryKey(taskId), allowRetryCurrentAction)
            .commit()
        if (!markerSaved) {
            preferences.edit().remove(pendingKey).remove(pendingAtKey(taskId))
                .remove(afterHandoffKey(taskId)).remove(allowRetryKey(taskId)).apply()
            error("Phone Agent 调度标记无法持久化，已取消本次入队")
        }
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
        // Keep the durable marker if WorkManager rejects enqueueing. Startup reconciliation
        // can retry after the grace window instead of orphaning a planned/running task.
        WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .enqueue()
    }

    /**
     * Reconciles pending markers against WorkManager's persisted unique queue.
     * A stale marker with no live WorkRequest represents the crash window between
     * writing SharedPreferences and enqueueing WorkManager.
     */
    suspend fun reconcilePending(context: Context, activeTaskIds: Set<String>) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val workInfos: List<androidx.work.WorkInfo> = try {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(QUEUE_NAME)
                .get()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext
        }
        val activeWorkTags = workInfos.filterNot { it.state.isFinished }
            .flatMap { it.tags }
            .toSet()

        val markerPrefix = "phone_agent_work_pending_"
        val pendingTaskIds = preferences.all.keys
            .filter { it.startsWith(markerPrefix) && !it.startsWith("phone_agent_work_pending_at_") }
            .map { it.removePrefix(markerPrefix) }
        val now = System.currentTimeMillis()
        pendingTaskIds.forEach { taskId ->
            if (uniqueName(taskId) in activeWorkTags) return@forEach
            val pendingAt = preferences.getLong(pendingAtKey(taskId), 1L)
            if (!PhoneAgentPendingWorkPolicy.shouldReconcile(pendingAt, now, workExists = false)) return@forEach
            val afterHandoff = preferences.getBoolean(afterHandoffKey(taskId), false)
            val allowRetry = preferences.getBoolean(allowRetryKey(taskId), false)
            if (taskId in activeTaskIds) {
                // Do not clear the marker before enqueue succeeds. If WorkManager throws,
                // the marker remains durable and can be reconciled on the next startup.
                enqueue(appContext, taskId, afterHandoff, allowRetry, allowExistingMarker = true)
            } else {
                preferences.edit().remove(pendingKey(taskId)).remove(pendingAtKey(taskId))
                    .remove(afterHandoffKey(taskId)).remove(allowRetryKey(taskId)).remove(cancelKey(taskId)).apply()
            }
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
    internal fun pendingAtKey(taskId: String) = "phone_agent_work_pending_at_$taskId"
    private fun uniqueName(taskId: String) = "nestro-phone-agent-$taskId"
}
