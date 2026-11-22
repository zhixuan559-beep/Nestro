/*
 * Isolated-display adapter for the shared Phone Agent execution contract.
 * The accessibility service reads only windows belonging to this display;
 * input and task movement are routed through the Mika shell helper.
 */
package app.nestro.phone.virtualdisplay.mika

import app.nestro.phone.PhoneAgentExecutionEnvironment
import app.nestro.phone.PhoneAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

class MikaPhoneAgentExecutionEnvironment(
    val service: PhoneAgentService,
    private val session: MikaVirtualDisplayController.Session,
) : PhoneAgentExecutionEnvironment {
    private val handedOffTasks = ConcurrentHashMap<String, Int>()

    override fun hasValidGrant() = service.hasValidGrant()
    override fun isTaskStopped(taskId: String) = service.isTaskStopped(taskId)
    override fun requestTaskStop(taskId: String) = service.requestTaskStop(taskId)
    override fun captureVisibleText() = service.captureVisibleText(session.displayId)
    override fun clickVisibleText(exactText: String) = service.clickVisibleText(session.displayId, exactText)
    override fun setTextOnVisibleField(label: String, value: String) =
        service.setTextOnVisibleField(session.displayId, label, value)
    override fun scroll(direction: String) = service.scroll(session.displayId, direction)
    override fun openUrl(rawUrl: String) = runBlocking(Dispatchers.IO) { session.openUrl(rawUrl) == 0 }
    override fun launchPackage(packageName: String) = runBlocking(Dispatchers.IO) { session.launchApp(packageName) == 0 }
    override fun pressBack() = runBlocking(Dispatchers.IO) { session.back() }
    override fun verifyVisibleText(expected: String) = service.verifyVisibleText(session.displayId, expected)
    override fun activePackageName() = runBlocking(Dispatchers.IO) { session.topTaskPackage() }
    override fun screenTextChanged(before: String) = service.screenTextChanged(session.displayId, before)
    override fun downloadAsset(taskId: String, projectId: String, url: String, displayName: String) =
        service.downloadAsset(taskId, projectId, url, displayName)

    override fun handoffToUser(taskId: String, reason: String): Boolean = runBlocking(Dispatchers.IO) {
        val taskIdOnDisplay = session.topTaskId()
        if (taskIdOnDisplay < 0 || !session.moveTaskToMain(taskIdOnDisplay)) return@runBlocking false
        handedOffTasks[taskId] = taskIdOnDisplay
        true
    }

    override fun resumeAfterUserHandoff(taskId: String): Boolean = runBlocking(Dispatchers.IO) {
        val taskIdOnDisplay = handedOffTasks.remove(taskId) ?: return@runBlocking true
        session.moveTaskBack(taskIdOnDisplay)
    }
}
