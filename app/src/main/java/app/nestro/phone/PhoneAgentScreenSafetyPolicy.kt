package app.nestro.phone

/**
 * Deterministic, fail-closed handoff gate for authentication, anti-abuse,
 * consent and Android permission screens. Checked before every device action.
 */
object PhoneAgentScreenSafetyPolicy {
    private val directHandoffSignals = listOf(
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
        "unusual activity detected",
        "accept all cookies",
        "accept cookies",
        "cookie preferences",
        "by clicking accept you agree",
        "i agree to the terms",
        "agree and continue",
        "accept and continue",
        "同意并继续",
        "接受并继续",
        "接受全部cookie",
        "请阅读并同意",
        "我已阅读并同意",
        "请勾选同意",
        "隐私政策与用户协议",
        "权限请求",
        "请求访问权限"
    )

    private val permissionVerbs = listOf(
        "是否允许",
        "允许",
        "允许访问",
        "使用应用时允许",
        "仅限这一次",
        "allow while using",
        "only this time",
        "don't allow",
        "do not allow",
        "allow access",
        "allow nestro to"
    )

    private val permissionResources = listOf(
        "位置", "通知", "相机", "摄像头", "麦克风", "照片", "视频",
        "文件", "通讯录", "location", "notifications", "camera",
        "microphone", "photos", "videos", "files", "contacts"
    )

    fun requiresUserHandoff(screenText: String): Boolean {
        val normalized = screenText.replace(Regex("\\s+"), "").lowercase()
        if (directHandoffSignals.any { normalized.contains(it.replace(Regex("\\s+"), "").lowercase()) }) {
            return true
        }
        val hasPermissionVerb = permissionVerbs.any {
            normalized.contains(it.replace(Regex("\\s+"), "").lowercase())
        }
        val hasPermissionResource = permissionResources.any {
            normalized.contains(it.replace(Regex("\\s+"), "").lowercase())
        }
        return hasPermissionVerb && hasPermissionResource
    }
}
