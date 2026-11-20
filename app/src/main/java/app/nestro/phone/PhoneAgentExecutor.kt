package app.nestro.phone

import app.nestro.data.NestroDao
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.runCatchingCancellable
import app.nestro.agent.AgentAuthorization
import app.nestro.agent.AgentPermissionRouter
import app.nestro.agent.AgentRuntime
import app.nestro.agent.AgentTaskState
import app.nestro.agent.AgentTaskStatus
import app.nestro.agent.AgentToolPermission
import app.nestro.agent.AgentToolRequest
import app.nestro.agent.AgentToolResult
import app.nestro.agent.AgentToolSpec
import app.nestro.agent.AgentToolLeaseRegistry
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.DefaultAgentRuntime
import app.nestro.agent.InMemoryAgentToolCatalog
import app.nestro.agent.RoomAgentTaskStateStore
import app.nestro.agent.RoomAgentTaskTraceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/** Executes typed actions only. Model prose is never interpreted as a click command. */
object PhoneAgentExecutor {
    sealed class Action {
        data class OpenUrl(val url: String) : Action()
        data class LaunchApp(val packageName: String) : Action()
        data class ClickText(val exactText: String, val expectedAfter: String? = null) : Action()
        data class EnterText(val fieldLabel: String, val value: String) : Action()
        data class Scroll(val direction: String) : Action()
        data class Wait(val millis: Long) : Action()
        data class VerifyText(val expected: String) : Action()
        object Back : Action()
        data class ConfirmSensitive(val description: String) : Action()
    }
    enum class Outcome { COMPLETED, PAUSED, WAITING_USER, FAILED, CANCELLED }
    data class ActionResult(val index: Int, val action: String, val success: Boolean, val detail: String)
    data class ExecutionResult(val outcome: Outcome, val completedActions: Int, val results: List<ActionResult>, val handoffReason: String = "")

    suspend fun execute(dao: NestroDao, taskId: String, actions: List<Action>, markTaskCompleted: Boolean = true, catalog: AgentToolCatalog = InMemoryAgentToolCatalog(), onProgress: suspend (Int, ActionResult) -> Unit = { _, _ -> }): ExecutionResult {
        require(actions.size in 1..80) { "任务动作数量必须为 1 到 80" }
        PhoneAgentTaskStore.persistActions(dao, taskId, actions)
        return runFrom(dao, taskId, actions, 0, markTaskCompleted, catalog, onProgress)
    }

    /** Resume only previously persisted actions. If waiting for the user, skip the handoff marker after user completion. */
    suspend fun resume(dao: NestroDao, taskId: String, afterUserHandoff: Boolean = false, allowRetryCurrentAction: Boolean = false, catalog: AgentToolCatalog = InMemoryAgentToolCatalog(), onProgress: suspend (Int, ActionResult) -> Unit = { _, _ -> }): ExecutionResult {
        val task = dao.phoneAgentTask(taskId) ?: return ExecutionResult(Outcome.FAILED, 0, emptyList(), "任务不存在")
        val actionCount = try { PhoneAgentTaskStore.decodeActions(task.planJson).size } catch (_: Exception) { 0 }
        if (task.status in setOf(PhoneAgentTaskStore.STATUS_RUNNING, PhoneAgentTaskStore.STATUS_FAILED) && task.currentStep < actionCount && !allowRetryCurrentAction) {
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep)
            return ExecutionResult(Outcome.PAUSED, 0, emptyList(), "任务曾在动作执行期间中断。请先核对手机当前页面，再点击继续。")
        }
        val actions = try {
            PhoneAgentTaskStore.decodeActions(task.planJson)
        } catch (error: Exception) {
            return ExecutionResult(Outcome.FAILED, 0, emptyList(), error.message ?: "动作计划无法恢复")
        }
        val start = if (afterUserHandoff && task.status == PhoneAgentTaskStore.STATUS_WAITING_USER &&
            actions.getOrNull(task.currentStep) is Action.ConfirmSensitive) task.currentStep + 1 else task.currentStep
        return runFrom(dao, taskId, actions, start, true, catalog, onProgress)
    }

    private suspend fun runFrom(dao: NestroDao, taskId: String, actions: List<Action>, startIndex: Int, markTaskCompleted: Boolean, catalog: AgentToolCatalog, onProgress: suspend (Int, ActionResult) -> Unit): ExecutionResult {
        val task = dao.phoneAgentTask(taskId) ?: return ExecutionResult(Outcome.FAILED, 0, emptyList(), "任务不存在")
        if (task.status == PhoneAgentTaskStore.STATUS_CANCELLED) {
            return ExecutionResult(Outcome.CANCELLED, 0, emptyList(), "任务已取消")
        }
        val agentStates = RoomAgentTaskStateStore(dao)
        val service = PhoneAgentExecutionEnvironmentProvider.active
        if (service == null) {
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep)
            agentStates.put(AgentTaskState(taskId, "phone_agent", AgentTaskStatus.PAUSED, task.currentStep, task.planJson.take(64_000), "无障碍服务尚未连接"))
            return ExecutionResult(Outcome.PAUSED, 0, emptyList(), "无障碍服务尚未连接")
        }
        if (!service.hasValidGrant()) {
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep)
            agentStates.put(AgentTaskState(taskId, "phone_agent", AgentTaskStatus.PAUSED, task.currentStep, task.planJson.take(64_000), "没有有效的操作授权"))
            return ExecutionResult(Outcome.PAUSED, 0, emptyList(), "没有有效的操作授权")
        }
        val results = mutableListOf<ActionResult>()
        agentStates.put(
            AgentTaskState(
                taskId = taskId,
                owner = "phone_agent",
                status = AgentTaskStatus.RUNNING,
                currentStep = startIndex,
                checkpoint = task.planJson.take(64_000)
            )
        )
        PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_RUNNING, startIndex)
        var action: Action = actions.getOrNull(startIndex) ?: Action.Wait(0)
        val toolPermissionRouter = AgentPermissionRouter { _, _ ->
                when {
                    service.isTaskStopped(taskId) -> AgentAuthorization.Deny("用户已请求停止")
                    !service.hasValidGrant() -> AgentAuthorization.Deny("操作授权已失效")
                    else -> AgentAuthorization.Allow
                }
            }
        for (index in startIndex until actions.size) {
            val latestTask = dao.phoneAgentTask(taskId)
                ?: return ExecutionResult(Outcome.FAILED, results.size, results, "任务不存在")
            if (latestTask.status == PhoneAgentTaskStore.STATUS_CANCELLED) {
                return ExecutionResult(Outcome.CANCELLED, results.size, results, "任务已取消")
            }
            if (service.isTaskStopped(taskId)) {
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, index)
                agentStates.put(AgentTaskState(taskId, "phone_agent", AgentTaskStatus.PAUSED, index, task.planJson.take(64_000), "用户已请求停止"))
                return ExecutionResult(Outcome.PAUSED, results.size, results, "用户已请求停止")
            }
            if (!service.hasValidGrant()) {
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, index)
                agentStates.put(AgentTaskState(taskId, "phone_agent", AgentTaskStatus.PAUSED, index, task.planJson.take(64_000), "操作授权已失效"))
                return ExecutionResult(Outcome.PAUSED, results.size, results, "操作授权已失效")
            }
            action = actions[index]
            if (action is Action.ConfirmSensitive) {
                PhoneAgentTaskStore.recordActionResult(dao, taskId, index, "等待用户", false, action.description)
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_WAITING_USER, index)
                agentStates.put(AgentTaskState(taskId, "phone_agent", AgentTaskStatus.WAITING_FOR_USER, index, task.planJson.take(64_000), action.description))
                RoomAgentTaskTraceStore(dao).append(app.nestro.agent.AgentTaskTrace(taskId = taskId, event = "waiting_for_user", detail = action.description.take(2_000)))
                return ExecutionResult(Outcome.WAITING_USER, results.size, results, action.description)
            }
            val toolId = toolIdFor(action)
            catalog.register(
                AgentToolSpec(
                    id = toolId,
                    description = "执行当前步骤指定的手机动作：" + action.javaClass.simpleName,
                    permission = AgentToolPermission.DEVICE_ACTION
                )
            ) {
                        val executingAction = action
                        val attempt = runCatchingCancellable {
                            when (executingAction) {
                                is Action.OpenUrl -> {
                                    val before = service.captureVisibleText()
                                    val opened = service.openUrl(executingAction.url)
                                    delay(1200)
                                    opened && service.screenTextChanged(before)
                                }
                                is Action.LaunchApp -> {
                                    val beforePackage = service.activePackageName()
                                    val beforeText = service.captureVisibleText()
                                    val launched = service.launchPackage(executingAction.packageName)
                                    delay(1000)
                                    launched && (service.activePackageName() != beforePackage || service.screenTextChanged(beforeText))
                                }
                                is Action.ClickText -> {
                                    val before = service.captureVisibleText()
                                    val clicked = service.clickVisibleText(executingAction.exactText)
                                    if (clicked) {
                                        delay(500)
                                        if (executingAction.expectedAfter != null) service.verifyVisibleText(executingAction.expectedAfter)
                                        else service.screenTextChanged(before)
                                    } else false
                                }
                                is Action.EnterText -> service.setTextOnVisibleField(executingAction.fieldLabel, executingAction.value)
                                is Action.Scroll -> {
                                    val before = service.captureVisibleText()
                                    val scrolled = service.scroll(executingAction.direction)
                                    if (scrolled) { delay(350); service.screenTextChanged(before) } else false
                                }
                                is Action.Wait -> { delay(executingAction.millis.coerceIn(0, 10_000)); true }
                                is Action.VerifyText -> service.verifyVisibleText(executingAction.expected)
                                Action.Back -> {
                                    val beforePackage = service.activePackageName()
                                    val beforeText = service.captureVisibleText()
                                    val backed = service.pressBack()
                                    if (backed) { delay(350); service.activePackageName() != beforePackage || service.screenTextChanged(beforeText) } else false
                                }
                                is Action.ConfirmSensitive -> false
                            }
                        }
                        val failure = attempt.exceptionOrNull()
                        val success = attempt.getOrDefault(false)
                        val detail = failure?.message ?: if (success) "动作已执行并核验" else "目标未匹配或动作未成功"
                        AgentToolResult(success = success, output = detail, error = if (success) "" else detail)
                    }
            val toolLease = AgentToolLeaseRegistry.open(taskId, catalog, setOf(toolId))
            val runtime: AgentRuntime = DefaultAgentRuntime(
                catalog = toolLease.access,
                permissions = toolPermissionRouter,
                states = agentStates,
                traces = RoomAgentTaskTraceStore(dao)
            )
            val toolResult = try {
                runtime.execute(AgentToolRequest(taskId, toolId))
            } finally {
                AgentToolLeaseRegistry.release(toolLease.id)
                catalog.remove(toolId)
            }
            val success = toolResult.success
            val detail = if (success) toolResult.output.ifBlank { "动作已执行并核验" }
                else toolResult.error.ifBlank { "目标未匹配或动作未成功" }
            val result = ActionResult(index, actionLabel(action), success, detail)
            results += result
            PhoneAgentTaskStore.recordActionResult(dao, taskId, index, result.action, result.success, result.detail)
            onProgress(index + 1, result)
            if (!success) {
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_FAILED, index)
                return ExecutionResult(Outcome.FAILED, results.count { it.success }, results, detail)
            }
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_RUNNING, index + 1)
        }
        PhoneAgentTaskStore.updateProgress(
            dao, taskId,
            if (markTaskCompleted) PhoneAgentTaskStore.STATUS_COMPLETED else PhoneAgentTaskStore.STATUS_RUNNING,
            actions.size
        )
        agentStates.put(
            AgentTaskState(
                taskId, "phone_agent",
                if (markTaskCompleted) AgentTaskStatus.COMPLETED else AgentTaskStatus.RUNNING,
                actions.size, task.planJson.take(64_000), ""
            )
        )
        return ExecutionResult(Outcome.COMPLETED, results.size, results)
    }
    private fun actionLabel(action: Action): String = when (action) {
        is Action.OpenUrl -> {
            val uri = runCatching { java.net.URI(action.url) }.getOrNull()
            if (uri?.host in setOf("html.duckduckgo.com", "www.google.com", "www.bing.com")) {
                val encodedQuery = uri.rawQuery.orEmpty().substringAfter("q=", "")
                val query = runCatching { java.net.URLDecoder.decode(encodedQuery, "UTF-8") }.getOrDefault("")
                "网页搜索：" + PhoneAgentRiskPolicy.redactSensitiveText(query).take(80)
            } else {
                "打开网页：" + (uri?.host ?: "网页")
            }
        }
        is Action.LaunchApp -> "打开应用：" + action.packageName.take(80)
        is Action.ClickText -> "点击目标：" + PhoneAgentRiskPolicy.redactSensitiveText(action.exactText).take(80)
        is Action.EnterText -> "填写字段：" + action.fieldLabel.take(60) + "（${action.value.length} 字符，内容不记录）"
        is Action.Scroll -> if (action.direction == "forward") "向下滚动" else "向上滚动"
        is Action.Wait -> "等待 ${action.millis.coerceIn(0, 10_000)} ms"
        is Action.VerifyText -> "核验页面文字：" + PhoneAgentRiskPolicy.redactSensitiveText(action.expected).take(80)
        Action.Back -> "返回上一页"
        is Action.ConfirmSensitive -> "等待用户处理：" + PhoneAgentRiskPolicy.redactSensitiveText(action.description).take(100)
    }

    private fun toolIdFor(action: Action): String = when (action) {
        is Action.OpenUrl -> "phone.open_url"
        is Action.LaunchApp -> "phone.launch_app"
        is Action.ClickText -> "phone.click"
        is Action.EnterText -> "phone.enter_text"
        is Action.Scroll -> "phone.scroll"
        is Action.Wait -> "phone.wait"
        is Action.VerifyText -> "phone.verify"
        Action.Back -> "phone.back"
        is Action.ConfirmSensitive -> "phone.confirm_sensitive"
    }

}
