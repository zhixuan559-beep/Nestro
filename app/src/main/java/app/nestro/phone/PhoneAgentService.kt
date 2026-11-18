package app.nestro.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class PhoneAgentService : AccessibilityService(), PhoneAgentExecutionEnvironment {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        PhoneAgentExecutionEnvironmentProvider.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately do not inspect event content. Screen capture is user-triggered.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (activeService === this) activeService = null
        PhoneAgentExecutionEnvironmentProvider.detach(this)
        super.onDestroy()
    }

    override fun captureVisibleText(): String {
        // Keep observation read-only and bounded. A future virtual-screen renderer can consume this snapshot
        // without taking input focus or changing the foreground application's window.
        val root = rootInActiveWindow ?: return "当前页面没有可读取的无障碍节点。"
        val lines = mutableListOf<String>()
        collect(root, lines, 0)
        val result = lines.take(120).joinToString("\n").ifBlank { "当前页面没有可读取的文本。" }
        audit("SCREEN_READ", "读取当前可访问界面文本")
        return result
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 12 || out.size >= 120) return
        if (!node.isPassword) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val value = redactSensitiveText((text.ifBlank { desc }).take(180))
            if (value.isNotBlank()) out += value
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collect(child, out, depth + 1) }
            if (out.size >= 120) break
        }
    }

    override fun hasValidGrant(): Boolean { val p = getSharedPreferences("nestro_local", MODE_PRIVATE); return p.getLong("phone_task_grant_until", 0L) > System.currentTimeMillis() || p.getBoolean("phone_single_grant", false) }
    override fun isTaskStopped(): Boolean = getSharedPreferences("nestro_local", MODE_PRIVATE).getBoolean("phone_task_stop_requested", false)
    fun clearTaskStop() { getSharedPreferences("nestro_local", MODE_PRIVATE).edit().remove("phone_task_stop_requested").apply() }
    override fun requestTaskStop() { getSharedPreferences("nestro_local", MODE_PRIVATE).edit().putBoolean("phone_task_stop_requested", true).apply(); audit("TASK_STOP", "用户请求停止当前任务") }

    override fun clickVisibleText(exactText: String): Boolean {
        val normalized = exactText.trim()
        require(normalized.isNotBlank() && normalized.length <= 80) { "目标文本无效" }
        require(!isHighRisk(normalized)) { "该操作属于高风险操作，必须由用户本人完成确认" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootInActiveWindow ?: return false
        val node = findVisibleNode(root, normalized) { true } ?: return false
        val target = clickableAncestor(node) ?: return false
        val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        consumeSingleGrantIfNeeded()
        audit("CLICK", redactSensitiveText(normalized))
        return success
    }

    override fun setTextOnVisibleField(label: String, value: String): Boolean {
        val target = label.trim()
        require(target.isNotBlank() && target.length <= 80) { "输入框定位文本无效" }
        require(value.length <= 1000) { "输入内容过长" }
        require(!isHighRisk(value)) { "输入内容触发高风险规则，必须由用户本人处理" }
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

    override fun scroll(direction: String): Boolean {
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

    override fun openUrl(rawUrl: String): Boolean {
        val uri = android.net.Uri.parse(rawUrl.trim())
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) { "仅允许打开 HTTP/HTTPS 网页" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "网址无效或包含嵌入式账号信息" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        consumeSingleGrantIfNeeded()
        audit("OPEN_URL", uri.host ?: "网页")
        return true
    }

    override fun launchPackage(packageName: String): Boolean {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "应用标识无效" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        consumeSingleGrantIfNeeded()
        audit("LAUNCH_APP", packageName)
        return true
    }

    override fun pressBack(): Boolean {
        require(hasValidGrant()) { "没有有效的操作授权" }
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        consumeSingleGrantIfNeeded()
        audit("BACK", "返回")
        return success
    }

    override fun verifyVisibleText(expected: String): Boolean {
        val needle = expected.trim()
        if (needle.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val found = findVisibleNode(root, needle) { true } != null
        audit("VERIFY", "目标文本=" + redactSensitiveText(needle.take(60)) + "，结果=" + found)
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


    /** Read-only snapshot for a non-focus-stealing virtual-screen layer. */
    fun captureReadOnlySnapshot(maxLines: Int = 120, maxChars: Int = 12000): String {
        return captureVisibleText().lines().take(maxLines).joinToString("\n").take(maxChars)
    }

    fun hasActiveWindow(): Boolean = rootInActiveWindow != null
    override fun activePackageName(): String? = rootInActiveWindow?.packageName?.toString()

    override fun screenTextChanged(before: String): Boolean = captureVisibleText() != before

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable) return node
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; val found = findScrollableNode(child); if (found != null) return found }
        return null
    }

    private fun consumeSingleGrantIfNeeded() {
        val p = getSharedPreferences("nestro_local", MODE_PRIVATE)
        if (p.getLong("phone_task_grant_until", 0L) <= System.currentTimeMillis() && p.getBoolean("phone_single_grant", false)) p.edit().remove("phone_single_grant").apply()
    }

    private fun isSensitiveLabel(value: String): Boolean = listOf("密码", "验证码", "短信码", "支付码", "银行卡", "身份证", "安全码", "cvv", "pin", "手机号", "手机号码", "电话", "邮箱", "电子邮件", "收货地址", "详细地址", "地址", "联系人", "收货人", "住址", "账号").any { value.contains(it, true) }

    private fun redactSensitiveText(value: String): String = value
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[邮箱已隐藏]")
        .replace(Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[手机号已隐藏]")
        .replace(Regex("(?<!\\d)\\d{16,19}(?!\\d)"), "[疑似卡号已隐藏]")
        .replace(Regex("(?<!\\d)\\d{6}(?!\\d)"), "[六位数字已隐藏]")
        .replace(Regex("(?<![0-9Xx])[0-9]{17}[0-9Xx](?![0-9Xx])"), "[身份证号已隐藏]")
        .replace(Regex("(收货地址|详细地址|家庭地址|住址|地址)[:：]\\s*[^，,；;。]+"), "$1：[地址已隐藏]")
        .replace(Regex("(收货人|联系人|姓名)[:：]\\s*[^，,；;。]+"), "$1：[姓名已隐藏]")

    private fun isHighRisk(value: String): Boolean {
        val terms = listOf("支付", "付款", "转账", "银行卡", "密码", "验证码", "删除账号", "确认购买", "购买", "立即购买", "预约", "提交订单", "提交", "下单", "确认", "立即下单", "确认支付", "发送验证码", "发送密码", "发送银行卡", "发送身份证", "上传通讯录", "分享屏幕", "确认提交", "退款")
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
