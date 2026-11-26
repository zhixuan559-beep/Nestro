package app.nestro.phone

/**
 * Host-independent execution surface for Phone Agent actions.
 *
 * The isolated VirtualDisplay adapter is the only execution environment.
 * Android AccessibilityService is used only to inspect and target nodes on that
 * virtual display; it is never attached as a real-screen execution fallback.
 */
interface PhoneAgentExecutionEnvironment {
    fun hasValidGrant(): Boolean
    fun isTaskStopped(taskId: String): Boolean
    fun requestTaskStop(taskId: String)
    fun captureVisibleText(): String
    fun clickVisibleText(exactText: String): Boolean
    /** 按千分比坐标（0..1000）点按：用于无文字的图标控件，或多个同名目标中点指定的一个。 */
    fun clickAt(xPermille: Int, yPermille: Int): Boolean
    fun setTextOnVisibleField(label: String, value: String): Boolean
    fun scroll(direction: String): Boolean
    fun openUrl(rawUrl: String): Boolean
    fun launchPackage(packageName: String): Boolean
    fun pressBack(): Boolean
    fun verifyVisibleText(expected: String): Boolean
    fun activePackageName(): String?
    fun screenTextChanged(before: String): Boolean
    /** Download a public media URL and import it into the task's isolated project. */
    fun downloadAsset(taskId: String, projectId: String, url: String, displayName: String, sourceUrl: String, licenseLabel: String): String? = null

    /** Explicitly move the current app task to the real display for user takeover. */
    fun handoffToUser(taskId: String, reason: String): Boolean = true

    /** Return a handed-off task to the selected execution display after user confirmation. */
    fun resumeAfterUserHandoff(taskId: String): Boolean = true
}

/**
 * Exactly one execution environment is active at a time. No automatic fallback
 * is performed: if the selected isolated environment detaches, the task pauses
 * instead of silently taking control of the user's real screen.
 */
object PhoneAgentExecutionEnvironmentProvider {
    @Volatile
    var active: PhoneAgentExecutionEnvironment? = null
        private set

    fun attach(environment: PhoneAgentExecutionEnvironment) {
        active = environment
    }

    fun detach(environment: PhoneAgentExecutionEnvironment) {
        if (active === environment) active = null
    }
}
