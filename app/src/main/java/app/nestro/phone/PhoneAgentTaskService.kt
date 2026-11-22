package app.nestro.phone

import android.content.Context
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.data.NestroDatabase
import app.nestro.data.PhoneAgentTaskEntity
import app.nestro.data.PhoneAgentTaskLogEntity
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Application boundary for Phone Agent task persistence and background scheduling.
 * Compose screens render task state; this service owns side effects and lifecycle transitions.
 */
class PhoneAgentTaskService(
    context: Context,
    private val database: NestroDatabase,
    private val coordinator: AgentTaskCoordinator
) {
    private val appContext = context.applicationContext

    suspend fun loadTasks(projectId: String, limit: Int = 30): List<PhoneAgentTaskEntity> =
        database.dao().phoneAgentTasks(projectId, limit.coerceIn(1, 100))

    suspend fun loadLogs(
        tasks: List<PhoneAgentTaskEntity>,
        limit: Int = 12
    ): Map<String, List<PhoneAgentTaskLogEntity>> {
        val dao = database.dao()
        return tasks.associate { task ->
            task.id to runCatchingCancellable {
                PhoneAgentTaskStore.recentLogs(dao, task.id, limit.coerceIn(1, 100))
            }.getOrDefault(emptyList())
        }
    }

    suspend fun createAndScheduleIfAuthorized(
        projectId: String,
        conversationId: String,
        plan: PhoneAgentPlanner.Plan
    ): PhoneAgentTaskEntity {
        val task = PhoneAgentTaskStore.create(database.dao(), projectId, conversationId, plan)
        if (PhoneAgentExecutionEnvironmentProvider.active?.hasValidGrant() == true) enqueue(task.id)
        return task
    }

    fun enqueue(
        taskId: String,
        afterUserHandoff: Boolean = false,
        allowRetryCurrentAction: Boolean = false
    ) {
        PhoneAgentWorkScheduler.enqueue(
            appContext,
            taskId,
            afterUserHandoff,
            allowRetryCurrentAction
        )
    }

    suspend fun appendClarification(taskId: String, clarification: String) {
        PhoneAgentTaskStore.appendClarification(database.dao(), taskId, clarification)
    }

    suspend fun cancel(taskId: String, stopCurrent: Boolean) {
        // Commit the terminal state first. A stale UI tap must not cancel a task
        // whose completion was already persisted by the worker.
        val cancelled = PhoneAgentTaskStore.cancelIfActive(database.dao(), taskId)
        if (!cancelled) return
        withContext(NonCancellable) {
            PhoneAgentWorkScheduler.cancel(appContext, taskId, stopCurrent)
            PhoneAgentNotifications.cancel(appContext, taskId)
            coordinator.cancel(taskId)
        }
    }
}
