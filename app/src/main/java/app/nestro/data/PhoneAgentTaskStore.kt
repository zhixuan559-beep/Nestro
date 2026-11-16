package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentExecutor
import java.util.UUID

object PhoneAgentTaskStore {
    const val STATUS_PLANNED = "planned"
    const val STATUS_RUNNING = "running"
    const val STATUS_WAITING_USER = "waiting_user"
    const val STATUS_PAUSED = "paused"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"

    suspend fun create(dao: NestroDao, projectId: String, conversationId: String, plan: PhoneAgentPlanner.Plan): PhoneAgentTaskEntity {
        val now = System.currentTimeMillis()
        val task = PhoneAgentTaskEntity(
            id = UUID.randomUUID().toString(), projectId = projectId, conversationId = conversationId,
            goal = plan.goal, category = plan.category.name, status = STATUS_PLANNED,
            planJson = encodePlan(plan), currentStep = 0, createdAt = now, updatedAt = now
        )
        dao.putPhoneAgentTask(task)
        return task
    }

    suspend fun persistActions(dao: NestroDao, taskId: String, actions: List<PhoneAgentExecutor.Action>) {
        require(actions.size in 1..80) { "任务动作数量必须为 1 到 80" }
        val rows = JSONArray()
        actions.forEach { action ->
            val row = JSONObject()
            when (action) {
                is PhoneAgentExecutor.Action.OpenUrl -> row.put("type", "open_url").put("url", action.url)
                is PhoneAgentExecutor.Action.LaunchApp -> row.put("type", "launch_app").put("package", action.packageName)
                is PhoneAgentExecutor.Action.ClickText -> row.put("type", "click").put("text", action.exactText).put("expectedAfter", action.expectedAfter ?: JSONObject.NULL)
                is PhoneAgentExecutor.Action.EnterText -> row.put("type", "enter_text").put("label", action.fieldLabel).put("value", action.value)
                is PhoneAgentExecutor.Action.Scroll -> row.put("type", "scroll").put("direction", action.direction)
                is PhoneAgentExecutor.Action.Wait -> row.put("type", "wait").put("millis", action.millis)
                is PhoneAgentExecutor.Action.VerifyText -> row.put("type", "verify").put("text", action.expected)
                PhoneAgentExecutor.Action.Back -> row.put("type", "back")
                is PhoneAgentExecutor.Action.ConfirmSensitive -> row.put("type", "confirm_sensitive").put("description", action.description)
            }
            rows.put(row)
        }
        val encoded = JSONObject().put("format", "phone_agent_actions_v1").put("actions", rows).toString()
        require(encoded.length <= 120_000) { "任务动作数据过大" }
        check(dao.phoneAgentTask(taskId) != null) { "任务不存在" }
        dao.updatePhoneAgentTaskPlan(taskId, encoded, System.currentTimeMillis())
    }

    fun decodeActions(planJson: String): List<PhoneAgentExecutor.Action> {
        val root = JSONObject(planJson)
        require(root.optString("format") == "phone_agent_actions_v1") { "任务没有可恢复的动作计划" }
        val rows = root.getJSONArray("actions")
        require(rows.length() in 1..80) { "动作计划数量无效" }
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            when (row.getString("type")) {
                "open_url" -> PhoneAgentExecutor.Action.OpenUrl(row.getString("url"))
                "launch_app" -> PhoneAgentExecutor.Action.LaunchApp(row.getString("package"))
                "click" -> PhoneAgentExecutor.Action.ClickText(row.getString("text"), row.optString("expectedAfter").takeIf { it.isNotBlank() && it != "null" })
                "enter_text" -> PhoneAgentExecutor.Action.EnterText(row.getString("label"), row.getString("value"))
                "scroll" -> PhoneAgentExecutor.Action.Scroll(row.getString("direction"))
                "wait" -> PhoneAgentExecutor.Action.Wait(row.getLong("millis"))
                "verify" -> PhoneAgentExecutor.Action.VerifyText(row.getString("text"))
                "back" -> PhoneAgentExecutor.Action.Back
                "confirm_sensitive" -> PhoneAgentExecutor.Action.ConfirmSensitive(row.getString("description"))
                else -> error("任务包含未知动作类型")
            }
        }
    }

    suspend fun recordActionResult(
        dao: NestroDao,
        taskId: String,
        index: Int,
        action: String,
        success: Boolean,
        detail: String
    ) {
        dao.insertPhoneAgentTaskLog(
            PhoneAgentTaskLogEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                actionIndex = index,
                action = action.take(60),
                success = success,
                detail = detail.take(240),
                createdAt = System.currentTimeMillis()
            )
        )
        dao.trimPhoneAgentTaskLogs(taskId, 100)
    }

    suspend fun recentLogs(dao: NestroDao, taskId: String, limit: Int = 30): List<PhoneAgentTaskLogEntity> =
        dao.phoneAgentTaskLogs(taskId, limit).reversed()

    suspend fun appendClarification(dao: NestroDao, taskId: String, clarification: String) {
        val clean = clarification.trim().take(500)
        require(clean.length >= 2) { "补充内容太短" }
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val updatedGoal = (task.goal + "\\n用户补充说明：" + clean).take(2000)
        require(updatedGoal.length > task.goal.length) { "补充内容无效" }
        val now = System.currentTimeMillis()
        dao.updatePhoneAgentTaskGoal(taskId, updatedGoal, now)
        dao.updatePhoneAgentTaskPlan(taskId, "[]", now)
    }

    suspend fun updateProgress(dao: NestroDao, taskId: String, status: String, currentStep: Int) {
        require(status in setOf(STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER, STATUS_PAUSED, STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED)) { "未知任务状态" }
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val actionCount = runCatching { JSONObject(task.planJson).getJSONArray("actions").length() }.getOrNull()
        val planCount = actionCount ?: runCatching { JSONArray(task.planJson).length() }.getOrDefault(0)
        require(currentStep in 0..planCount) { "任务步骤位置无效" }
        dao.updatePhoneAgentTaskProgress(taskId, status, currentStep, System.currentTimeMillis())
    }

    suspend fun resumeCandidates(dao: NestroDao, projectId: String): List<PhoneAgentTaskEntity> =
        dao.phoneAgentTasks(projectId, 50).filter { it.status in setOf(STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER, STATUS_PAUSED) }

    private fun encodePlan(plan: PhoneAgentPlanner.Plan): String {
        val steps = JSONArray()
        plan.steps.forEach { step ->
            steps.put(JSONObject().put("kind", step.kind.name).put("title", step.title).put("risk", step.risk.name).put("requiresUserConfirmation", step.requiresUserConfirmation))
        }
        return steps.toString()
    }
}
