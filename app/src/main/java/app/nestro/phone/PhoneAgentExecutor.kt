package app.nestro.phone

import app.nestro.data.NestroDao
import app.nestro.data.PhoneAgentTaskStore
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
    enum class Outcome { COMPLETED, PAUSED, WAITING_USER, FAILED }
    data class ActionResult(val index: Int, val action: String, val success: Boolean, val detail: String)
    data class ExecutionResult(val outcome: Outcome, val completedActions: Int, val results: List<ActionResult>, val handoffReason: String = "")

    suspend fun execute(dao: NestroDao, taskId: String, actions: List<Action>, markTaskCompleted: Boolean = true, onProgress: suspend (Int, ActionResult) -> Unit = { _, _ -> }): ExecutionResult {
        require(actions.size in 1..80) { "任务动作数量必须为 1 到 80" }
        PhoneAgentTaskStore.persistActions(dao, taskId, actions)
        return runFrom(dao, taskId, actions, 0, markTaskCompleted, onProgress)
    }

    /** Resume only previously persisted actions. If waiting for the user, skip the handoff marker after user completion. */
    suspend fun resume(dao: NestroDao, taskId: String, afterUserHandoff: Boolean = false, allowRetryCurrentAction: Boolean = false, onProgress: suspend (Int, ActionResult) -> Unit = { _, _ -> }): ExecutionResult {
        val task = dao.phoneAgentTask(taskId) ?: return ExecutionResult(Outcome.FAILED, 0, emptyList(), "任务不存在")
        if (task.status == PhoneAgentTaskStore.STATUS_RUNNING && task.currentStep < runCatching { PhoneAgentTaskStore.decodeActions(task.planJson).size }.getOrDefault(0) && !allowRetryCurrentAction) {
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep)
            return ExecutionResult(Outcome.PAUSED, 0, emptyList(), "任务曾在动作执行期间中断。请先核对手机当前页面，再点击继续。")
        }
        val actions = runCatching { PhoneAgentTaskStore.decodeActions(task.planJson) }
            .getOrElse { return ExecutionResult(Outcome.FAILED, 0, emptyList(), it.message ?: "动作计划无法恢复") }
        val start = if (afterUserHandoff && task.status == PhoneAgentTaskStore.STATUS_WAITING_USER &&
            actions.getOrNull(task.currentStep) is Action.ConfirmSensitive) task.currentStep + 1 else task.currentStep
        return runFrom(dao, taskId, actions, start, true, onProgress)
    }

    private suspend fun runFrom(dao: NestroDao, taskId: String, actions: List<Action>, startIndex: Int, markTaskCompleted: Boolean, onProgress: suspend (Int, ActionResult) -> Unit): ExecutionResult {
        val service = PhoneAgentService.activeService ?: return ExecutionResult(Outcome.PAUSED, 0, emptyList(), "无障碍服务尚未连接")
        val task = dao.phoneAgentTask(taskId) ?: return ExecutionResult(Outcome.FAILED, 0, emptyList(), "任务不存在")
        if (!service.hasValidGrant()) {
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, task.currentStep)
            return ExecutionResult(Outcome.PAUSED, 0, emptyList(), "没有有效的操作授权")
        }
        val results = mutableListOf<ActionResult>()
        PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_RUNNING, startIndex)
        for (index in startIndex until actions.size) {
            if (service.isTaskStopped()) {
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, index)
                return ExecutionResult(Outcome.PAUSED, results.size, results, "用户已请求停止")
            }
            if (!service.hasValidGrant()) {
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, index)
                return ExecutionResult(Outcome.PAUSED, results.size, results, "操作授权已失效")
            }
            val action = actions[index]
            if (action is Action.ConfirmSensitive) {
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_WAITING_USER, index)
                return ExecutionResult(Outcome.WAITING_USER, results.size, results, action.description)
            }
            val attempt = runCatching {
                when (action) {
                    is Action.OpenUrl -> service.openUrl(action.url)
                    is Action.LaunchApp -> service.launchPackage(action.packageName)
                    is Action.ClickText -> {
                        val clicked = service.clickVisibleText(action.exactText)
                        if (clicked && action.expectedAfter != null) { delay(500); service.verifyVisibleText(action.expectedAfter) } else clicked
                    }
                    is Action.EnterText -> service.setTextOnVisibleField(action.fieldLabel, action.value)
                    is Action.Scroll -> service.scroll(action.direction)
                    is Action.Wait -> { delay(action.millis.coerceIn(0, 10_000)); true }
                    is Action.VerifyText -> service.verifyVisibleText(action.expected)
                    Action.Back -> service.pressBack()
                    is Action.ConfirmSensitive -> false
                }
            }
            val failure = attempt.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val success = attempt.getOrDefault(false)
            val detail = failure?.message ?: if (success) "动作已执行并核验" else "目标未匹配或动作未成功"
            val result = ActionResult(index, action.javaClass.simpleName, success, detail)
            results += result
            PhoneAgentTaskStore.recordActionResult(dao, taskId, index, result.action, result.success, result.detail)
            onProgress(index + 1, result)
            if (!success) {
                PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_PAUSED, index)
                return ExecutionResult(Outcome.PAUSED, results.count { it.success }, results, detail)
            }
            PhoneAgentTaskStore.updateProgress(dao, taskId, PhoneAgentTaskStore.STATUS_RUNNING, index + 1)
        }
        PhoneAgentTaskStore.updateProgress(
            dao, taskId,
            if (markTaskCompleted) PhoneAgentTaskStore.STATUS_COMPLETED else PhoneAgentTaskStore.STATUS_RUNNING,
            actions.size
        )
        return ExecutionResult(Outcome.COMPLETED, results.size, results)
    }
}
