package app.nestro.phone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class PhoneAgentService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately do not inspect event content. Screen capture is user-triggered.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    fun captureVisibleText(): String {
        val root = rootInActiveWindow ?: return "当前页面没有可读取的无障碍节点。"
        val lines = mutableListOf<String>()
        collect(root, lines, 0)
        val result = lines.take(120).joinToString("\n").ifBlank { "当前页面没有可读取的文本。" }
        audit("SCREEN_READ", "用户主动读取当前界面")
        return result
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 12 || out.size >= 120) return
        if (!node.isPassword) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val value = (text.ifBlank { desc }).take(180)
            if (value.isNotBlank()) out += value
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collect(child, out, depth + 1) }
            if (out.size >= 120) break
        }
    }

    fun clickVisibleText(exactText: String): Boolean {
        val normalized = exactText.trim()
        require(normalized.isNotBlank() && normalized.length <= 80) { "目标文本无效" }
        require(!isHighRisk(normalized)) { "该操作属于高风险操作，必须由用户本人完成确认" }
        val prefs = getSharedPreferences("nestro_local", MODE_PRIVATE)
        val taskUntil = prefs.getLong("phone_task_grant_until", 0L)
        val single = prefs.getBoolean("phone_single_grant", false)
        require(taskUntil > System.currentTimeMillis() || single) { "没有有效的操作授权" }
        val root = rootInActiveWindow ?: return false
        val node = root.findAccessibilityNodeInfosByText(normalized)
            .firstOrNull { it.isVisibleToUser && it.isClickable }
            ?: return false
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (single && taskUntil <= System.currentTimeMillis()) {
            prefs.edit().remove("phone_single_grant").apply()
        }
        audit("CLICK", normalized)
        return success
    }

    private fun isHighRisk(value: String): Boolean {
        val terms = listOf("支付", "付款", "转账", "银行卡", "密码", "验证码", "删除账号", "确认购买", "立即购买", "发送")
        return terms.any { value.contains(it, ignoreCase = true) }
    }

    private fun audit(action: String, detail: String) {
        val prefs = getSharedPreferences("nestro_local", MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString("phone_audit", "[]")) }.getOrDefault(JSONArray())
        old.put(JSONObject().put("at", System.currentTimeMillis()).put("action", action).put("detail", detail.take(180)))
        val trimmed = JSONArray()
        for (i in (old.length() - 100).coerceAtLeast(0) until old.length()) trimmed.put(old.getJSONObject(i))
        prefs.edit().putString("phone_audit", trimmed.toString()).apply()
    }

    companion object {
        @Volatile var activeService: PhoneAgentService? = null
            private set
    }
}
