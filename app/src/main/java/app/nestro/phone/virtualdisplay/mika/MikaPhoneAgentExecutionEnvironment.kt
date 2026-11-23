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

class MikaPhoneAgentExecutionEnvironment(
    val service: PhoneAgentService,
    private val session: MikaVirtualDisplayController.Session,
) : PhoneAgentExecutionEnvironment {
    private val preferences = service.getSharedPreferences("nestro_local", android.content.Context.MODE_PRIVATE)
    private fun handoffKey(taskId: String) = "mika_handoff_" + taskId.take(80)

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
    override fun downloadAsset(taskId: String, projectId: String, url: String, displayName: String, sourceUrl: String, licenseLabel: String) =
        service.downloadAsset(taskId, projectId, url, displayName, sourceUrl, licenseLabel)

    override fun handoffToUser(taskId: String, reason: String): Boolean = runBlocking(Dispatchers.IO) {
        val taskIdOnDisplay = session.topTaskId()
        if (taskIdOnDisplay < 0) return@runBlocking false
        // Persist the task mapping before moving it, so process death cannot orphan a foreground handoff.
        val saved = preferences.edit().putInt(handoffKey(taskId), taskIdOnDisplay).commit()
        if (!saved) return@runBlocking false
        val moved = session.moveTaskToMain(taskIdOnDisplay)
        if (!moved) preferences.edit().remove(handoffKey(taskId)).commit()
        moved
    }

    override fun resumeAfterUserHandoff(taskId: String): Boolean = runBlocking(Dispatchers.IO) {
        val taskIdOnDisplay = preferences.getInt(handoffKey(taskId), -1)
        if (taskIdOnDisplay < 0) return@runBlocking true
        val moved = session.moveTaskBack(taskIdOnDisplay)
        if (moved) preferences.edit().remove(handoffKey(taskId)).commit()
        moved
    }
}
