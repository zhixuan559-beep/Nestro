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
        "cookie consent",
        "cookie settings",
        "manage cookies",
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
        "文件", "通讯录", "蓝牙", "附近设备", "日历", "存储", "location", "notifications", "camera",
        "microphone", "photos", "videos", "files", "contacts", "bluetooth",
        "nearby devices", "calendar", "storage", "media"
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
        if (hasPermissionVerb && hasPermissionResource) return true

        val consentTerms = listOf("terms of service", "user agreement", "privacy policy", "用户协议", "服务条款", "隐私政策")
        val consentVerbs = listOf("agree", "accept", "同意", "接受", "确认")
        return consentTerms.any { normalized.contains(it.replace(Regex("\\s+"), "").lowercase()) } &&
            consentVerbs.any { normalized.contains(it.replace(Regex("\\s+"), "").lowercase()) }
    }

    /**
     * A route preview is safe to inspect, but starting turn-by-turn navigation is a
     * user decision. Do not rely on the model to remember to emit confirm_sensitive.
     */
    fun requiresNavigationStartHandoff(screenText: String, targetLabel: String): Boolean {
        val screen = screenText.lowercase().replace(Regex("\\s+"), "")
        val target = targetLabel.lowercase().replace(Regex("[\\s：:，,。.!！?？、_-]+"), "")
        val explicitNavigationStart = listOf(
            "开始导航", "启动导航", "立即导航", "startnavigation", "navigatenow", "navigate"
        ).any(target::contains)
        if (explicitNavigationStart) return true

        val isDirectionsScreen = listOf(
            "googlemaps", "路线", "路线预览", "directions", "route", "预计到达", "到达时间"
        ).any(screen::contains)
        val genericStart = target in setOf("开始", "出发", "导航", "start", "go")
        return isDirectionsScreen && genericStart
    }
}
