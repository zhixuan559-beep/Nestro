package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import app.nestro.phone.PhoneAgentPlanner
import java.util.UUID

object PhoneAgentTaskStore {
    const val STATUS_PLANNED = "planned"
    const val STATUS_RUNNING = "running"
    const val STATUS_WAITING_USER = "waiting_user"
    const val STATUS_PAUSED = "paused"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"

    suspend fun create(
        dao: NestroDao,
        projectId: String,
        conversationId: String,
        plan: PhoneAgentPlanner.Plan
    ): PhoneAgentTaskEntity {
        val now = System.currentTimeMillis()
        val task = PhoneAgentTaskEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            conversationId = conversationId,
            goal = plan.goal,
            category = plan.category.name,
            status = STATUS_PLANNED,
            planJson = encodePlan(plan),
            currentStep = 0,
            createdAt = now,
            updatedAt = now
        )
        dao.putPhoneAgentTask(task)
        return task
    }

    suspend fun updateProgress(
        dao: NestroDao,
        taskId: String,
        status: String,
        currentStep: Int
    ) {
        require(status in setOf(
            STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER,
            STATUS_PAUSED, STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED
        )) { "未知任务状态" }
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val stepCount = runCatching { JSONArray(task.planJson).length() }.getOrDefault(0)
        require(currentStep in 0..stepCount) { "任务步骤位置无效" }
        dao.updatePhoneAgentTaskProgress(taskId, status, currentStep, System.currentTimeMillis())
    }

    suspend fun resumeCandidates(dao: NestroDao, projectId: String): List<PhoneAgentTaskEntity> =
        dao.phoneAgentTasks(projectId, 50).filter {
            it.status in setOf(STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER, STATUS_PAUSED)
        }

    private fun encodePlan(plan: PhoneAgentPlanner.Plan): String {
        val steps = JSONArray()
        plan.steps.forEach { step ->
            steps.put(
                JSONObject()
                    .put("kind", step.kind.name)
                    .put("title", step.title)
                    .put("risk", step.risk.name)
                    .put("requiresUserConfirmation", step.requiresUserConfirmation)
            )
        }
        return steps.toString()
    }
}
