package app.nestro.phone

/** Fail-closed target checks shared by the UI adapter and JVM tests. */
object PhoneAgentTargetPolicy {
    fun canClick(matchCount: Int, targetVisible: Boolean, targetEnabled: Boolean): Boolean =
        matchCount == 1 && targetVisible && targetEnabled

    fun canFillField(matchCount: Int, fieldVisible: Boolean, fieldEnabled: Boolean, passwordField: Boolean): Boolean =
        matchCount == 1 && fieldVisible && fieldEnabled && !passwordField
}
