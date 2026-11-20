package app.nestro.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class PhoneAgentService : AccessibilityService(), PhoneAgentExecutionEnvironment {
    private data class TextUndoRecord(val label: String, val previousValue: String, val appliedValue: String, val screenSnapshot: String)
    @Volatile private var lastTextUndo: TextUndoRecord? = null

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
        lastTextUndo = null
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
        if (node.isVisibleToUser && !node.isPassword) {
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
    override fun isTaskStopped(taskId: String): Boolean =
        getSharedPreferences("nestro_local", MODE_PRIVATE).getBoolean(PhoneAgentWorkScheduler.stopKey(taskId), false)
    fun clearTaskStop(taskId: String) {
        getSharedPreferences("nestro_local", MODE_PRIVATE).edit()
            .remove(PhoneAgentWorkScheduler.stopKey(taskId)).apply()
    }
    override fun requestTaskStop(taskId: String) {
        getSharedPreferences("nestro_local", MODE_PRIVATE).edit()
            .putBoolean(PhoneAgentWorkScheduler.stopKey(taskId), true).apply()
        audit("TASK_STOP", "用户请求停止任务 " + taskId.take(48))
    }

    override fun clickVisibleText(exactText: String): Boolean {
        val normalized = exactText.trim()
        require(normalized.isNotBlank() && normalized.length <= 80) { "目标文本无效" }
        require(!PhoneAgentRiskPolicy.requiresUserHandoffForClick(normalized)) { "该操作属于高风险操作，必须由用户本人完成确认" }
        require(!PhoneAgentRiskPolicy.containsSensitiveValue(normalized)) { "页面目标包含个人敏感信息，已阻止点击" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootInActiveWindow ?: return false
        val matches = findVisibleNodes(root, normalized) { true }
        if (matches.size != 1) {
            audit("CLICK_BLOCKED", if (matches.isEmpty()) "未找到唯一可见目标" else "存在多个同名目标，已阻止点击")
            return false
        }
        val target = clickableAncestor(matches.single()) ?: return false
        if (!PhoneAgentTargetPolicy.canClick(matches.size, target.isVisibleToUser, target.isEnabled)) {
            audit("CLICK_BLOCKED", "目标不可见或不可用")
            return false
        }
        val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        consumeSingleGrantIfNeeded()
        audit("CLICK", redactSensitiveText(normalized))
        return success
    }

    override fun setTextOnVisibleField(label: String, value: String): Boolean {
        val target = label.trim()
        require(target.isNotBlank() && target.length <= 80) { "输入框定位文本无效" }
        require(value.length <= 1000) { "输入内容过长" }
        require(!PhoneAgentRiskPolicy.containsSensitiveValue(value)) { "输入内容包含敏感信息，必须由用户本人处理" }
        require(!PhoneAgentRiskPolicy.isSensitiveField(target)) { "密码、验证码及敏感字段必须由用户本人填写" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootInActiveWindow ?: return false
        val matches = findEditableFields(root, target)
        if (matches.size != 1) {
            audit("SET_TEXT_BLOCKED", if (matches.isEmpty()) "未找到唯一可见输入框" else "存在多个同名输入框，已阻止填写")
            return false
        }
        val node = matches.single()
        if (!PhoneAgentTargetPolicy.canFillField(
                matches.size,
                node.isVisibleToUser,
                node.isEnabled,
                node.isPassword
            )
        ) {
            audit("SET_TEXT_BLOCKED", "输入框不可见、不可用或属于密码字段")
            return false
        }
        val previousValue = node.text?.toString().orEmpty()
        if (PhoneAgentRiskPolicy.redactSensitiveText(previousValue) != previousValue) {
            audit("SET_TEXT_BLOCKED", "原输入框包含敏感信息，已阻止覆盖以避免保存敏感撤销数据")
            return false
        }
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val success = performed && node.text?.toString() == value
        if (success) {
            lastTextUndo = if (PhoneAgentRiskPolicy.redactSensitiveText(value) == value) {
                TextUndoRecord(target, previousValue, value, captureVisibleText())
            } else null
        }
        consumeSingleGrantIfNeeded()
        audit("SET_TEXT", "字段=$target，字符数=" + value.length)
        return success
    }

    fun canUndoLastTextEdit(): Boolean = lastTextUndo != null

    fun undoLastTextEdit(): Boolean {
        val record = lastTextUndo ?: return false
        if (!hasValidGrant() || PhoneAgentRiskPolicy.isSensitiveField(record.label) ||
            PhoneAgentRiskPolicy.redactSensitiveText(record.previousValue) != record.previousValue ||
            PhoneAgentRiskPolicy.redactSensitiveText(record.appliedValue) != record.appliedValue
        ) return false
        val currentSnapshot = captureVisibleText()
        if (currentSnapshot != record.screenSnapshot) return false
        val root = rootInActiveWindow ?: return false
        val matches = findEditableFields(root, record.label)
        if (matches.size != 1) return false
        val node = matches.single()
        if (!PhoneAgentTargetPolicy.canFillField(matches.size, node.isVisibleToUser, node.isEnabled, node.isPassword) ||
            node.text?.toString().orEmpty() != record.appliedValue
        ) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, record.previousValue)
        }
        val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val restored = performed && node.text?.toString().orEmpty() == record.previousValue
        if (restored) {
            lastTextUndo = null
            consumeSingleGrantIfNeeded()
            audit("UNDO_SET_TEXT", "已撤销字段填写：${record.label.take(60)}")
        }
        return restored
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
        require(PhoneAgentUrlPolicy.isSafePublicWebUrl(rawUrl) && !PhoneAgentRiskPolicy.containsSensitiveValue(rawUrl)) { "仅允许打开安全且不含个人敏感信息的公开网页" }
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

    private fun findVisibleNodes(root: AccessibilityNodeInfo, text: String, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByText(text).filter {
            it.isVisibleToUser && predicate(it) &&
                (it.text?.toString()?.trim()?.equals(text, true) == true ||
                    it.contentDescription?.toString()?.trim()?.equals(text, true) == true)
        }

    private fun findVisibleNode(root: AccessibilityNodeInfo, text: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        findVisibleNodes(root, text, predicate).firstOrNull()

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val item = current ?: return null
            if (item.isClickable) return item
            current = item.parent
        }
        return null
    }

    private fun findEditableFields(node: AccessibilityNodeInfo, label: String): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun isEditableTarget(current: AccessibilityNodeInfo): Boolean {
            if (!current.isVisibleToUser || !current.isEditable || !current.isEnabled || current.isPassword) return false
            val hint = if (android.os.Build.VERSION.SDK_INT >= 26) current.hintText?.toString().orEmpty() else ""
            return hint.equals(label, true) ||
                current.contentDescription?.toString()?.equals(label, true) == true ||
                current.text?.toString()?.equals(label, true) == true
        }
        fun collectEditable(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || matches.size >= 20) return
            if (current.isVisibleToUser && current.isEditable && current.isEnabled && !current.isPassword) {
                matches += current
            }
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { collectEditable(it, depth + 1) }
            }
        }
        fun visit(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || matches.size >= 20) return
            if (isEditableTarget(current)) matches += current
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                val childLabel = child.text?.toString()?.trim().orEmpty().equals(label, true) ||
                    child.contentDescription?.toString()?.trim().orEmpty().equals(label, true)
                if (childLabel) collectEditable(child, depth + 1) else visit(child, depth + 1)
            }
        }
        visit(node, 0)
        return matches.distinctBy { it.hashCode() }
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


    private fun redactSensitiveText(value: String): String = PhoneAgentRiskPolicy.redactSensitiveText(value)


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
