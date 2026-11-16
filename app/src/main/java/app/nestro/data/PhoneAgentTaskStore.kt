package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentExecutor
import java.util.UUID
import java.net.URI

object PhoneAgentTaskStore {
    const val STATUS_PLANNED = "planned"
    const val STATUS_RUNNING = "running"
    const val STATUS_WAITING_USER = "waiting_user"
    const val STATUS_PAUSED = "paused"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_INTERRUPTED = "interrupted"
    const val STATUS_CANCELLED = "cancelled"

    fun statusLabel(status: String): String = when (status) {
        STATUS_PLANNED -> "待执行"
        STATUS_RUNNING -> "执行中"
        STATUS_WAITING_USER -> "等待你处理"
        STATUS_PAUSED -> "已暂停"
        STATUS_INTERRUPTED -> "中断待核对"
        STATUS_COMPLETED -> "已完成"
        STATUS_FAILED -> "执行异常"
        STATUS_CANCELLED -> "已取消"
        else -> "未知状态"
    }

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
        decodeActions(encoded)
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
                    val uri = runCatching { URI(url) }.getOrElse { error("恢复的网页地址无效") }
                    require(url.length <= 2048 && (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "恢复的网页地址不安全" }
                    PhoneAgentExecutor.Action.OpenUrl(url)
                }
                "launch_app" -> {
                    val packageName = row.getString("package").trim()
                    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "恢复的应用包名无效" }
                    PhoneAgentExecutor.Action.LaunchApp(packageName)
                }
                "click" -> {
                    val label = row.getString("text").trim()
                    val expectedAfter = row.optString("expectedAfter").takeIf { it.isNotBlank() && it != "null" }
                    require(label.isNotBlank() && label.length <= 80 && (expectedAfter == null || expectedAfter.length <= 100)) { "恢复的点击目标无效" }
                    PhoneAgentExecutor.Action.ClickText(label, expectedAfter)
                }
                "enter_text" -> {
                    val label = row.getString("label").trim()
                    val value = row.getString("value")
                    require(label.isNotBlank() && label.length <= 80 && value.length <= 1000) { "恢复的输入字段无效" }
                    require(!containsSensitiveLabel(label) && !containsSensitiveText(value)) { "恢复的动作包含个人敏感信息，必须由用户本人填写" }
                    PhoneAgentExecutor.Action.EnterText(label, value)
                }
                "scroll" -> {
                    val direction = row.getString("direction")
                    require(direction == "forward" || direction == "backward") { "恢复的滚动方向无效" }
                    PhoneAgentExecutor.Action.Scroll(direction)
                }
                "wait" -> {
                    val millis = row.getLong("millis")
                    require(millis in 0..10_000) { "恢复的等待时间无效" }
                    PhoneAgentExecutor.Action.Wait(millis)
                }
                "verify" -> {
                    val expected = row.getString("text").trim()
                    require(expected.isNotBlank() && expected.length <= 100) { "恢复的验证目标无效" }
                    PhoneAgentExecutor.Action.VerifyText(expected)
                }
                "back" -> PhoneAgentExecutor.Action.Back
                "confirm_sensitive" -> PhoneAgentExecutor.Action.ConfirmSensitive(redactSensitiveText(row.getString("description")).take(240))
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
                detail = redactSensitiveText(detail).take(240),
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
        require(!containsSensitiveClarification(clean)) { "请勿在这里填写地址、电话、验证码或其他敏感信息，请在目标应用中自行处理。" }
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val updatedGoal = task.goal + System.lineSeparator() + "用户补充说明：" + clean
        require(updatedGoal.length <= 2000) { "任务说明已达到长度上限" }
        val now = System.currentTimeMillis()
        dao.updatePhoneAgentTaskGoal(taskId, updatedGoal, now)
        dao.updatePhoneAgentTaskPlan(taskId, "[]", now)
        // updatePhoneAgentTaskPlan resets status and currentStep atomically.
    }

    suspend fun updateProgress(dao: NestroDao, taskId: String, status: String, currentStep: Int) {
        require(status in setOf(STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER, STATUS_PAUSED, STATUS_INTERRUPTED, STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED)) { "未知任务状态" }
        val task = dao.phoneAgentTask(taskId) ?: error("任务不存在")
        val actionCount = runCatching { JSONObject(task.planJson).getJSONArray("actions").length() }.getOrNull()
        val planCount = actionCount ?: runCatching { JSONArray(task.planJson).length() }.getOrDefault(0)
        require(currentStep in 0..planCount) { "任务步骤位置无效" }
        dao.updatePhoneAgentTaskProgress(taskId, status, currentStep, System.currentTimeMillis())
    }

    suspend fun pauseForUser(dao: NestroDao, taskId: String) {
        val task = dao.phoneAgentTask(taskId) ?: return
        val actionCount = runCatching { decodeActions(task.planJson).size }.getOrDefault(0)
        val ambiguousActionPending = task.status in setOf(STATUS_RUNNING, STATUS_INTERRUPTED) &&
            task.currentStep < actionCount
        val nextStatus = userPauseStatus(task.status, ambiguousActionPending)
        if (nextStatus != task.status) {
            updateProgress(dao, taskId, nextStatus, task.currentStep)
        }
    }

    fun userPauseStatus(currentStatus: String, hasAmbiguousAction: Boolean): String {
        if (currentStatus in setOf(STATUS_COMPLETED, STATUS_CANCELLED, STATUS_FAILED, STATUS_WAITING_USER)) {
            return currentStatus
        }
        return if (hasAmbiguousAction) STATUS_INTERRUPTED else STATUS_PAUSED
    }

    suspend fun markInterrupted(dao: NestroDao, taskId: String) {
        val task = dao.phoneAgentTask(taskId) ?: return
        val actionCount = runCatching { decodeActions(task.planJson).size }.getOrDefault(0)
        val ambiguousActionPending = task.status in setOf(STATUS_RUNNING, STATUS_INTERRUPTED) && task.currentStep < actionCount
        updateProgress(
            dao,
            taskId,
            interruptedStatus(task.status, ambiguousActionPending),
            task.currentStep
        )
    }

    fun interruptedStatus(currentStatus: String, hasAmbiguousAction: Boolean): String {
        if (currentStatus in setOf(STATUS_COMPLETED, STATUS_CANCELLED, STATUS_FAILED, STATUS_INTERRUPTED)) return currentStatus
        return if (hasAmbiguousAction) STATUS_INTERRUPTED else STATUS_PAUSED
    }

    suspend fun resumeCandidates(dao: NestroDao, projectId: String): List<PhoneAgentTaskEntity> =
        dao.phoneAgentTasks(projectId, 50).filter { it.status in setOf(STATUS_PLANNED, STATUS_RUNNING, STATUS_WAITING_USER, STATUS_PAUSED, STATUS_INTERRUPTED) }

    /** Remove common personal identifiers before any task context is sent to a model provider. */
    fun redactSensitiveText(value: String): String = value
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[邮箱已隐藏]")
        .replace(Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[手机号已隐藏]")
        .replace(Regex("(?<!\\d)\\d{16,19}(?!\\d)"), "[疑似卡号已隐藏]")
        .replace(Regex("(?<!\\d)\\d{6}(?!\\d)"), "[六位数字已隐藏]")
        .replace(Regex("(?<![0-9Xx])[0-9]{17}[0-9Xx](?![0-9Xx])"), "[身份证号已隐藏]")
        .replace(Regex("(收货地址|详细地址|家庭地址|住址|地址|收货地点|邮寄地址)[:：]?\\s*[^，,；;。]+")) { match -> match.groupValues[1] + "：[地址已隐藏]" }
        .replace(Regex("(收货人|联系人|姓名)[:：]?\\s*[^，,；;。]+")) { match -> match.groupValues[1] + "：[姓名已隐藏]" }
        .replace(Regex("(送到|寄到|住在|地址是)\\s*[^，,；;。]+")) { match -> match.groupValues[1] + "[地址已隐藏]" }

    fun containsSensitiveText(value: String): Boolean = redactSensitiveText(value) != value

    private fun containsSensitiveLabel(value: String): Boolean =
        listOf("密码", "验证码", "短信码", "支付码", "银行卡", "身份证", "安全码", "cvv", "pin", "手机号", "电话", "邮箱", "地址", "联系人", "收货人", "姓名", "账号")
            .any { value.contains(it, true) }

    private fun containsSensitiveClarification(value: String): Boolean =
        Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
            Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""").containsMatchIn(value) ||
            Regex("""(?<!\d)\d{6,19}(?!\d)""").containsMatchIn(value) ||
            listOf("地址", "住址", "电话", "手机号", "验证码", "密码", "银行卡", "身份证").any { value.contains(it, true) }

    private fun encodePlan(plan: PhoneAgentPlanner.Plan): String {
        val steps = JSONArray()
        plan.steps.forEach { step ->
            steps.put(JSONObject().put("kind", step.kind.name).put("title", step.title).put("risk", step.risk.name).put("requiresUserConfirmation", step.requiresUserConfirmation))
        }
        return steps.toString()
    }
}
