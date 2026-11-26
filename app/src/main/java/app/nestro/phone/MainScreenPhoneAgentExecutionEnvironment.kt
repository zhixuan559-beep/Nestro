package app.nestro.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Display

/**
 * 主屏兜底执行环境：无虚拟屏、无 ADB 时，经用户明确允许后在真实主屏上执行。
 *
 * 与隔离虚拟屏环境不同，它只在用户在界面上主动点按钮启用后才挂载，
 * 绝不自动接管主屏；虚拟屏环境在位时不会被它抢占。
 * 支付、验证码与最终提交仍由用户本人接手（PhoneAgentRiskPolicy / ScreenSafetyPolicy 不变）。
 */
class MainScreenPhoneAgentExecutionEnvironment(val service: PhoneAgentService) : PhoneAgentExecutionEnvironment {
    override fun hasValidGrant() = service.hasValidGrant()
    override fun isTaskStopped(taskId: String) = service.isTaskStopped(taskId)
    override fun requestTaskStop(taskId: String) = service.requestTaskStop(taskId)
    override fun captureVisibleText() = service.captureVisibleText(Display.DEFAULT_DISPLAY)
    override fun clickVisibleText(exactText: String) = service.clickVisibleText(Display.DEFAULT_DISPLAY, exactText)
    override fun setTextOnVisibleField(label: String, value: String) =
        service.setTextOnVisibleField(Display.DEFAULT_DISPLAY, label, value)
    override fun scroll(direction: String) = service.scroll(Display.DEFAULT_DISPLAY, direction)
    override fun openUrl(rawUrl: String): Boolean {
        if (!isOpenableUrl(rawUrl)) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            true
        }.getOrDefault(false)
    }
    override fun launchPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
    override fun pressBack() = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    override fun verifyVisibleText(expected: String) = service.verifyVisibleText(Display.DEFAULT_DISPLAY, expected)
    override fun activePackageName() = service.activePackageName(Display.DEFAULT_DISPLAY)
    override fun screenTextChanged(before: String) = service.screenTextChanged(Display.DEFAULT_DISPLAY, before)
    override fun downloadAsset(taskId: String, projectId: String, url: String, displayName: String, sourceUrl: String, licenseLabel: String) =
        service.downloadAsset(taskId, projectId, url, displayName, sourceUrl, licenseLabel)

    // 任务本就在用户主屏上，无需移动；交接即完成，恢复也无需操作。
    override fun handoffToUser(taskId: String, reason: String) = true
    override fun resumeAfterUserHandoff(taskId: String) = true

    companion object {
        const val MAIN_SCREEN_EXECUTION_ALLOWED_KEY = "phone_main_screen_execution_allowed"

        fun isMainScreenExecutionAllowed(context: Context): Boolean =
            context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getBoolean(MAIN_SCREEN_EXECUTION_ALLOWED_KEY, false)

        fun setMainScreenExecutionAllowed(context: Context, allowed: Boolean) {
            context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE).edit()
                .putBoolean(MAIN_SCREEN_EXECUTION_ALLOWED_KEY, allowed).apply()
        }

        /** 与虚拟屏会话的 openUrl 校验一致：仅 http/https 且带 host。
         * 用 java.net.URI 而不用 android.net.Uri：后者在 JVM 单测里是桩实现，
         * Uri.parse 会抛 Stub 异常、runCatching 吞掉后恒返回 false，
         * 本函数按设计是可单测的纯函数（见 MainScreenPhoneAgentExecutionEnvironmentTest）。 */
        fun isOpenableUrl(rawUrl: String): Boolean {
            val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
            return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }
    }
}
