package app.nestro.phone

/**
 * Host-independent execution surface for Phone Agent actions.
 *
 * The current Android AccessibilityService is one adapter. A future isolated
 * VirtualDisplay adapter can implement the same contract without changing the
 * planner, task scheduler, action executor, or permission lifecycle.
 */
interface PhoneAgentExecutionEnvironment {
    fun hasValidGrant(): Boolean
    fun isTaskStopped(taskId: String): Boolean
    fun requestTaskStop(taskId: String)
    fun captureVisibleText(): String
    fun clickVisibleText(exactText: String): Boolean
    fun setTextOnVisibleField(label: String, value: String): Boolean
    fun scroll(direction: String): Boolean
    fun openUrl(rawUrl: String): Boolean
    fun launchPackage(packageName: String): Boolean
    fun pressBack(): Boolean
    fun verifyVisibleText(expected: String): Boolean
    fun activePackageName(): String?
    fun screenTextChanged(before: String): Boolean
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
