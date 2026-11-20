package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentExecutor
import app.nestro.phone.PhoneAgentRiskPolicy
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
        require(plan.goal.isNotBlank() && plan.goal.length <= 2_000) { "任务目标为空或超出长度限制" }
        val now = System.currentTimeMillis()
        val task = PhoneAgentTaskEntity(
            id = UUID.randomUUID().toString(), projectId = projectId, conversationId = conversationId,
            goal = PhoneAgentRiskPolicy.redactSensitiveText(plan.goal), category = plan.category.name, status = STATUS_PLANNED,
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
                is PhoneAgentExecutor.Action.OpenUrl -> {
                    require(action.url.length <= 2048 && PhoneAgentUrlPolicy.isSafePublicWebUrl(action.url) && !PhoneAgentRiskPolicy.containsSensitiveValue(action.url)) { "网页地址不安全或包含个人敏感信息" }
                    row.put("type", "open_url").put("url", action.url)
                }
                is PhoneAgentExecutor.Action.LaunchApp -> {
                    require(action.packageName.length <= 255 && action.packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "应用标识无效" }
                    row.put("type", "launch_app").put("package", action.packageName)
                }
                is PhoneAgentExecutor.Action.ClickText -> {
                    require(
                        action.exactText.isNotBlank() && action.exactText.length <= 80 &&
                            (action.expectedAfter == null || (
                                action.expectedAfter.length <= 120 &&
                                    !PhoneAgentRiskPolicy.containsSensitiveValue(action.expectedAfter)
                            )) &&
                            !PhoneAgentRiskPolicy.requiresUserHandoffForClick(action.exactText) &&
                            !PhoneAgentRiskPolicy.containsSensitiveValue(action.exactText)
                    ) { "高风险、敏感或无效点击必须由用户处理" }
                    row.put("type", "click").put("text", action.exactText).put("expectedAfter", action.expectedAfter ?: JSONObject.NULL)
                }
                is PhoneAgentExecutor.Action.EnterText -> {
                    require(action.fieldLabel.isNotBlank() && action.fieldLabel.length <= 80 && action.value.length <= 1000 && !PhoneAgentRiskPolicy.isSensitiveField(action.fieldLabel) && !PhoneAgentRiskPolicy.containsSensitiveValue(action.fieldLabel) && !PhoneAgentRiskPolicy.containsSensitiveValue(action.value)) { "敏感或无效输入必须由用户本人处理" }
                    row.put("type", "enter_text").put("label", action.fieldLabel).put("value", action.value)
                }
                is PhoneAgentExecutor.Action.Scroll -> {
                    require(action.direction == "forward" || action.direction == "backward") { "滚动方向无效" }
                    row.put("type", "scroll").put("direction", action.direction)
                }
                is PhoneAgentExecutor.Action.Wait -> {
                    require(action.millis in 0..10_000) { "等待时间无效" }
                    row.put("type", "wait").put("millis", action.millis)
                }
                is PhoneAgentExecutor.Action.VerifyText -> {
                    require(action.expected.isNotBlank() && action.expected.length <= 100 && !PhoneAgentRiskPolicy.containsSensitiveValue(action.expected)) { "敏感或无效页面文字不能持久化为核验条件" }
                    row.put("type", "verify").put("text", action.expected)
                }
                PhoneAgentExecutor.Action.Back -> row.put("type", "back")
                is PhoneAgentExecutor.Action.ConfirmSensitive -> {
                    val description = PhoneAgentRiskPolicy.redactSensitiveText(action.description).take(240)
                    require(description.isNotBlank()) { "用户接手说明无效" }
                    row.put("type", "confirm_sensitive").put("description", description)
                }
            }
            rows.put(row)
        }
        val encoded = JSONObject().put("format", "phone_agent_actions_v1").put("actions", rows).toString()
        require(encoded.length <= 120_000) { "任务动作数据过大" }
        check(dao.phoneAgentTask(taskId) != null) { "任务不存在" }
        dao.updatePhoneAgentTaskPlan(taskId, encoded, System.currentTimeMillis())
    }

    fun validatePlanJson(planJson: String): Int {
        require(planJson.isNotBlank() && planJson.length <= 120_000) { "任务计划为空或超出大小限制" }
        val rootObject = runCatching { JSONObject(planJson) }.getOrNull()
        if (rootObject != null && rootObject.has("format")) {
            return decodeActions(planJson).size
        }
        val steps = runCatching { JSONArray(planJson) }.getOrElse { error("任务计划格式无效") }
        require(steps.length() in 0..80) { "任务步骤数量无效" }
        val validKinds = PhoneAgentPlanner.StepKind.values().map { it.name }.toSet()
        val validRisks = PhoneAgentPlanner.Risk.values().map { it.name }.toSet()
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: error("任务步骤格式无效")
            require(step.optString("kind") in validKinds) { "任务步骤类型无效" }
            require(step.optString("title").isNotBlank() && step.optString("title").length <= 300) { "任务步骤标题无效" }
            require(step.optString("risk") in validRisks) { "任务步骤风险等级无效" }
            require(step.has("requiresUserConfirmation")) { "任务步骤缺少确认策略" }
        }
        return steps.length()
    }

    fun decodeActions(planJson: String): List<PhoneAgentExecutor.Action> {
        val root = JSONObject(planJson)
        require(root.optString("format") == "phone_agent_actions_v1") { "任务没有可恢复的动作计划" }
        val rows = root.getJSONArray("actions")
        require(rows.length() in 1..80) { "动作计划数量无效" }
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            when (row.getString("type")) {
                "open_url" -> {
                    val url = row.getString("url").trim()
                    require(url.length <= 2048 && PhoneAgentUrlPolicy.isSafePublicWebUrl(url) && !PhoneAgentRiskPolicy.containsSensitiveValue(url)) { "已保存的网址不安全或包含个人敏感信息" }
                    PhoneAgentExecutor.Action.OpenUrl(url)
                }
                "launch_app" -> {
                    val packageName = row.getString("package").trim()
                    require(packageName.length <= 255 && packageName.matches(Regex("[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+"))) { "已保存的应用包名无效" }
                    PhoneAgentExecutor.Action.LaunchApp(packageName)
                }
                "click" -> {
                    val label = row.getString("text").trim()
                    val expectedAfter = row.optString("expectedAfter").takeIf { it.isNotBlank() && it != "null" }
                    require(
                        label.isNotBlank() && label.length <= 80 &&
                            (expectedAfter == null || (expectedAfter.length <= 120 && !PhoneAgentRiskPolicy.containsSensitiveValue(expectedAfter))) &&
                            !PhoneAgentRiskPolicy.containsSensitiveValue(label)
                    ) { "已保存的点击目标无效或包含个人敏感信息" }
                    require(!PhoneAgentRiskPolicy.requiresUserHandoffForClick(label)) { "已保存的高风险点击必须由用户重新确认" }
                    PhoneAgentExecutor.Action.ClickText(label, expectedAfter)
                }
                "enter_text" -> {
                    val label = row.getString("label").trim()
                    val value = row.getString("value")
                    require(
                        label.isNotBlank() && label.length <= 80 && value.length <= 1000 &&
                            !PhoneAgentRiskPolicy.isSensitiveField(label) &&
                            !PhoneAgentRiskPolicy.containsSensitiveValue(label) &&
                            !PhoneAgentRiskPolicy.containsSensitiveValue(value)
                    ) { "已保存的敏感输入必须由用户本人处理" }
                    PhoneAgentExecutor.Action.EnterText(label, value)
                }
                "scroll" -> {
                    val direction = row.getString("direction")
                    require(direction == "forward" || direction == "backward") { "已保存的滚动方向无效" }
                    PhoneAgentExecutor.Action.Scroll(direction)
                }
                "wait" -> {
                    val millis = row.getLong("millis")
                    require(millis in 0..10_000) { "已保存的等待时间无效" }
                    PhoneAgentExecutor.Action.Wait(millis)
                }
                "verify" -> {
                    val expected = row.getString("text").trim()
                    require(expected.isNotBlank() && expected.length <= 100 && !PhoneAgentRiskPolicy.containsSensitiveValue(expected)) { "已保存的验证目标无效或包含个人敏感信息" }
                    PhoneAgentExecutor.Action.VerifyText(expected)
                }
                "back" -> PhoneAgentExecutor.Action.Back
                "confirm_sensitive" -> {
                    val description = row.getString("description").trim()
                    require(
                        description.isNotBlank() && description.length <= 240 &&
                            !PhoneAgentRiskPolicy.containsSensitiveValue(description) &&
                            PhoneAgentRiskPolicy.redactSensitiveText(description) == description
                    ) { "已保存的用户确认说明无效或包含个人敏感信息" }
                    PhoneAgentExecutor.Action.ConfirmSensitive(description)
                }
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
                action = PhoneAgentRiskPolicy.redactSensitiveText(action).take(60),
                success = success,
                detail = PhoneAgentRiskPolicy.redactSensitiveText(detail).take(240),
                createdAt = System.currentTimeMillis()
            )
        )
        dao.trimPhoneAgentTaskLogs(taskId, 100)
    }

    suspend fun recentLogs(dao: NestroDao, taskId: String, limit: Int = 30): List<PhoneAgentTaskLogEntity> =
        dao.phoneAgentTaskLogs(taskId, limit).reversed()

    suspend fun appendClarification(dao: NestroDao, taskId: String, clarification: String) {
        val clean = PhoneAgentRiskPolicy.redactSensitiveText(clarification).trim().take(500)
        require(clean.length >= 2) { "补充内容太短" }
        require(!containsSensitiveClarification(clean)) { "请勿在这里填写地址、电话、验证码或其他敏感信息，请在目标应用中自行处理。" }
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val updatedGoal = task.goal + System.lineSeparator() + "用户补充说明：" + clean
        require(updatedGoal.length <= 2000) { "任务说明已达到长度上限" }
        val now = System.currentTimeMillis()
        dao.updatePhoneAgentTaskGoal(taskId, updatedGoal, now)
        dao.updatePhoneAgentTaskPlan(taskId, "[]", now)
    }

    suspend fun cancelIfActive(dao: NestroDao, taskId: String): Boolean =
        dao.cancelPhoneAgentTaskIfActive(taskId, System.currentTimeMillis()) > 0

    suspend fun updateProgress(dao: NestroDao, taskId: String, status: String, currentStep: Int) {
        require(status in setOf(STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER, STATUS_PAUSED, STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED)) { "未知任务状态" }
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val actionCount = runCatching { JSONObject(task.planJson).getJSONArray("actions").length() }.getOrNull()
        val planCount = actionCount ?: runCatching { JSONArray(task.planJson).length() }.getOrDefault(0)
        require(currentStep in 0..planCount) { "任务步骤位置无效" }
        dao.updatePhoneAgentTaskProgress(taskId, status, currentStep, System.currentTimeMillis())
    }

    suspend fun markInterrupted(dao: NestroDao, taskId: String) {
        val task = dao.phoneAgentTask(taskId) ?: return
        val actionCount = runCatching { decodeActions(task.planJson).size }.getOrDefault(0)
        val ambiguousActionPending = task.status == STATUS_RUNNING && task.currentStep < actionCount
        updateProgress(
            dao,
            taskId,
            if (ambiguousActionPending) STATUS_RUNNING else STATUS_PAUSED,
            task.currentStep
        )
    }

    suspend fun resumeCandidates(dao: NestroDao, projectId: String): List<PhoneAgentTaskEntity> =
        dao.phoneAgentTasks(projectId, 50).filter { it.status in setOf(STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER, STATUS_PAUSED) }

    private fun containsSensitiveClarification(value: String): Boolean =
        PhoneAgentRiskPolicy.containsSensitiveValue(value) ||
            PhoneAgentRiskPolicy.isSensitiveField(value)

    private fun encodePlan(plan: PhoneAgentPlanner.Plan): String {
        val steps = JSONArray()
        plan.steps.forEach { step ->
            steps.put(JSONObject().put("kind", step.kind.name).put("title", step.title).put("risk", step.risk.name).put("requiresUserConfirmation", step.requiresUserConfirmation))
        }
        return steps.toString()
    }
}
