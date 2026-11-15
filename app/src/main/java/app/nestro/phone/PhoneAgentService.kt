package app.nestro.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

    fun hasValidGrant(): Boolean { val p = getSharedPreferences("nestro_local", MODE_PRIVATE); return p.getLong("phone_task_grant_until", 0L) > System.currentTimeMillis() || p.getBoolean("phone_single_grant", false) }
    fun isTaskStopped(): Boolean = getSharedPreferences("nestro_local", MODE_PRIVATE).getBoolean("phone_task_stop_requested", false)
    fun clearTaskStop() { getSharedPreferences("nestro_local", MODE_PRIVATE).edit().remove("phone_task_stop_requested").apply() }
    fun requestTaskStop() { getSharedPreferences("nestro_local", MODE_PRIVATE).edit().putBoolean("phone_task_stop_requested", true).apply(); audit("TASK_STOP", "用户请求停止当前任务") }

    fun clickVisibleText(exactText: String): Boolean {
        val normalized = exactText.trim()
        require(normalized.isNotBlank() && normalized.length <= 80) { "目标文本无效" }
        require(!isHighRisk(normalized)) { "该操作属于高风险操作，必须由用户本人完成确认" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootInActiveWindow ?: return false
        val node = findVisibleNode(root, normalized) { true } ?: return false
        val target = clickableAncestor(node) ?: return false
        val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        consumeSingleGrantIfNeeded()
        audit("CLICK", normalized)
        return success
    }

    fun setTextOnVisibleField(label: String, value: String): Boolean {
        val target = label.trim()
        require(target.isNotBlank() && target.length <= 80) { "输入框定位文本无效" }
        require(value.length <= 1000) { "输入内容过长" }
        require(!isSensitiveLabel(target)) { "密码、验证码及敏感字段必须由用户本人填写" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootInActiveWindow ?: return false
        val node = findEditableField(root, target) ?: return false
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val success = performed && node.text?.toString() == value
        consumeSingleGrantIfNeeded()
        audit("SET_TEXT", "字段=$target，字符数=" + value.length)
        return success
    }

    fun scroll(direction: String): Boolean {
        require(direction == "forward" || direction == "backward") { "滚动方向无效" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootInActiveWindow ?: return false
        val node = findScrollableNode(root) ?: return false
        val action = if (direction == "forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = node.performAction(action)
        consumeSingleGrantIfNeeded()
        audit("SCROLL", direction)
        return success
    }

    fun openUrl(rawUrl: String): Boolean {
        val uri = android.net.Uri.parse(rawUrl.trim())
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) { "仅允许打开 HTTP/HTTPS 网页" }
        require(!uri.host.isNullOrBlank()) { "网址无效" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        consumeSingleGrantIfNeeded()
        audit("OPEN_URL", uri.host ?: "网页")
        return true
    }

    fun launchPackage(packageName: String): Boolean {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "应用标识无效" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        consumeSingleGrantIfNeeded()
        audit("LAUNCH_APP", packageName)
        return true
    }

    fun pressBack(): Boolean {
        require(hasValidGrant()) { "没有有效的操作授权" }
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        consumeSingleGrantIfNeeded()
        audit("BACK", "返回")
        return success
    }

    fun verifyVisibleText(expected: String): Boolean {
        val needle = expected.trim()
        if (needle.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val found = findVisibleNode(root, needle) { true } != null
        audit("VERIFY", "目标文本=" + needle.take(60) + "，结果=" + found)
        return found
    }

    private fun findVisibleNode(root: AccessibilityNodeInfo, text: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser && predicate(it) && (it.text?.toString()?.trim()?.equals(text, true) == true || it.contentDescription?.toString()?.trim()?.equals(text, true) == true) }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val item = current ?: return null
            if (item.isClickable) return item
            current = item.parent
        }
        return null
    }

    private fun findEditableField(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isEditable && node.isEnabled && !node.isPassword) {
            val hint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
            if (hint.equals(label, true) || node.contentDescription?.toString()?.equals(label, true) == true || node.text?.toString()?.equals(label, true) == true) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val direct = findEditableField(child, label)
            if (direct != null) return direct
            val childLabel = child.text?.toString()?.trim().orEmpty().equals(label, true) || child.contentDescription?.toString()?.trim().orEmpty().equals(label, true)
            if (childLabel) {
                for (j in 0 until child.childCount) {
                    val field = child.getChild(j) ?: continue
                    if (field.isVisibleToUser && field.isEditable && field.isEnabled && !field.isPassword) return field
                }
            }
        }
        return null
    }

    fun hasActiveWindow(): Boolean = rootInActiveWindow != null

    fun screenTextChanged(before: String): Boolean = captureVisibleText() != before

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable) return node
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; val found = findScrollableNode(child); if (found != null) return found }
        return null
    }

    private fun consumeSingleGrantIfNeeded() {
        val p = getSharedPreferences("nestro_local", MODE_PRIVATE)
        if (p.getLong("phone_task_grant_until", 0L) <= System.currentTimeMillis() && p.getBoolean("phone_single_grant", false)) p.edit().remove("phone_single_grant").apply()
    }

    private fun isSensitiveLabel(value: String): Boolean = listOf("密码", "验证码", "短信码", "支付码", "银行卡", "身份证", "安全码", "cvv", "pin").any { value.contains(it, true) }

    private fun isHighRisk(value: String): Boolean {
        val terms = listOf("支付", "付款", "转账", "银行卡", "密码", "验证码", "删除账号", "确认购买", "立即购买", "提交订单", "立即下单", "确认支付", "发送验证码", "发送密码", "发送银行卡", "发送身份证", "上传通讯录", "分享屏幕", "确认提交", "退款")
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
