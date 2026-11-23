package app.nestro.phone

import android.content.Context
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentTaskMetadata
import app.nestro.agent.AgentTaskState
import app.nestro.agent.AgentTaskStatus
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

    suspend fun loadTasks(projectId: String, limit: Int = 30): List<PhoneAgentTaskEntity> {
        val tasks = database.dao().phoneAgentTasks(projectId, limit.coerceIn(1, 100))
        return tasks.map { task ->
            val state = coordinator.state(task.id) ?: return@map task
            // Keep the domain status as the safety marker for ambiguous in-flight actions;
            // the coordinator remains authoritative for queue/lifecycle state.
            task.copy(currentStep = state.currentStep)
        }
    }

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
        coordinator.registerExternallyScheduled(
            AgentTaskState(
                taskId = task.id,
                owner = "phone_agent",
                status = AgentTaskStatus.CREATED,
                currentStep = task.currentStep,
                checkpoint = task.planJson.take(64_000),
                projectId = task.projectId,
                conversationId = task.conversationId,
                kind = "phone_agent",
                capabilityId = "phone_agent.execute",
                title = task.goal.take(160),
                input = task.goal.take(48_000)
            )
        )
        if (PhoneAgentExecutionEnvironmentProvider.active?.hasValidGrant() == true) enqueue(task.id)
        return task
    }

    suspend fun enqueue(
        taskId: String,
        afterUserHandoff: Boolean = false,
        allowRetryCurrentAction: Boolean = false
    ) {
        val task = database.dao().phoneAgentTask(taskId) ?: error("Phone Agent 任务不存在")
        ensureUnifiedState(task)
        val prepared = coordinator.prepareExternallyScheduled(
            taskId = taskId,
            owner = "phone_agent",
            checkpoint = task.planJson.take(64_000),
            metadata = AgentTaskMetadata(
                projectId = task.projectId,
                conversationId = task.conversationId,
                kind = "phone_agent",
                capabilityId = "phone_agent.execute",
                title = task.goal.take(160),
                input = task.goal.take(48_000)
            )
        ) ?: error("统一任务记录不存在，已阻止 Phone Agent 入队")
        check(prepared.status !in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) {
            "已结束的 Phone Agent 任务不能重新入队"
        }
        PhoneAgentWorkScheduler.enqueue(
            appContext,
            taskId,
            afterUserHandoff,
            allowRetryCurrentAction
        )
    }

    private suspend fun ensureUnifiedState(task: PhoneAgentTaskEntity) {
        if (coordinator.state(task.id) != null) return
        val status = when (task.status) {
            PhoneAgentTaskStore.STATUS_RUNNING -> AgentTaskStatus.RUNNING
            PhoneAgentTaskStore.STATUS_WAITING_USER -> AgentTaskStatus.WAITING_FOR_USER
            PhoneAgentTaskStore.STATUS_PAUSED -> AgentTaskStatus.PAUSED
            PhoneAgentTaskStore.STATUS_INTERRUPTED -> AgentTaskStatus.PAUSED
            PhoneAgentTaskStore.STATUS_COMPLETED -> AgentTaskStatus.COMPLETED
            PhoneAgentTaskStore.STATUS_FAILED -> AgentTaskStatus.FAILED
            PhoneAgentTaskStore.STATUS_CANCELLED -> AgentTaskStatus.CANCELLED
            else -> AgentTaskStatus.CREATED
        }
        coordinator.registerExternallyScheduled(
            AgentTaskState(
                taskId = task.id,
                owner = "phone_agent",
                status = status,
                currentStep = task.currentStep,
                checkpoint = task.planJson.take(64_000),
                projectId = task.projectId,
                conversationId = task.conversationId,
                kind = "phone_agent",
                capabilityId = "phone_agent.execute",
                title = task.goal.take(160),
                input = task.goal.take(48_000)
            )
        )
    }

    suspend fun appendClarification(taskId: String, clarification: String) {
        PhoneAgentTaskStore.appendClarification(database.dao(), taskId, clarification)
    }

    suspend fun cancel(taskId: String, stopCurrent: Boolean) {
        val task = database.dao().phoneAgentTask(taskId) ?: return
        ensureUnifiedState(task)
        val state = coordinator.state(taskId) ?: return
        if (state.status in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) return
        withContext(NonCancellable) {
            coordinator.cancel(taskId)
            PhoneAgentTaskStore.cancelIfActive(database.dao(), taskId)
            PhoneAgentWorkScheduler.cancel(appContext, taskId, stopCurrent)
            PhoneAgentNotifications.cancel(appContext, taskId)
        }
    }

}
