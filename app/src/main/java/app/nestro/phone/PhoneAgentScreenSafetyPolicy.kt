package app.nestro.phone

/** Deterministic stop gate for authentication, CAPTCHA and anti-abuse screens. */
object PhoneAgentScreenSafetyPolicy {
    private val handoffSignals = listOf(
        "请输入验证码",
        "短信验证码",
        "图形验证码",
        "人机验证",
        "滑动验证",
        "拖动滑块",
        "完成安全验证",
        "安全验证后继续",
        "访问受限",
        "异常流量",
        "检测到异常访问",
        "账号存在风险",
        "请先登录后继续",
        "登录后继续",
        "请输入登录密码",
        "enter verification code",
        "enter your password",
        "complete the captcha",
        "verify you are human",
        "security check required",
        "suspicious traffic",
        "login to continue",
        "sign in to continue",
        "unusual activity detected"
    )

    fun requiresUserHandoff(screenText: String): Boolean {
        val normalized = screenText.replace(Regex("\\s+"), "").lowercase()
        return handoffSignals.any { normalized.contains(it.lowercase()) }
    }
}
