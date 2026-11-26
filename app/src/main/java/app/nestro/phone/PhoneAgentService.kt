package app.nestro.phone

import android.accessibilityservice.AccessibilityService
import android.app.DownloadManager
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.os.Build
import android.view.accessibility.AccessibilityWindowInfo
import app.nestro.media.MediaProjectStore
import app.nestro.NestroApplication
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class PhoneAgentService : AccessibilityService() {
    private data class TextUndoRecord(val label: String, val previousValue: String, val appliedValue: String, val screenSnapshot: String)
    @Volatile private var lastTextUndo: TextUndoRecord? = null
    @Volatile private var activeMainScreenEnvironment: MainScreenPhoneAgentExecutionEnvironment? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        val controller = (applicationContext as? NestroApplication)?.mikaVirtualDisplayController
        if (controller?.virtualExecutionPreferred == true) {
            controller.activatePhoneAgent(this)
        }
        // 主屏兜底：虚拟屏未挂载、且用户此前明确允许过主屏执行时才挂载；绝不自动接管主屏。
        if (PhoneAgentExecutionEnvironmentProvider.active == null &&
            MainScreenPhoneAgentExecutionEnvironment.isMainScreenExecutionAllowed(applicationContext)
        ) {
            activateMainScreenExecution()
        }
    }

    /** 用户在界面上明确允许后，把 Phone Agent 挂到真实主屏；虚拟屏环境在位时不抢占。 */
    fun activateMainScreenExecution(): Boolean {
        if (PhoneAgentExecutionEnvironmentProvider.active != null) return false
        val environment = MainScreenPhoneAgentExecutionEnvironment(this)
        activeMainScreenEnvironment = environment
        PhoneAgentExecutionEnvironmentProvider.attach(environment)
        return true
    }

    fun deactivateMainScreenExecution() {
        activeMainScreenEnvironment?.let(PhoneAgentExecutionEnvironmentProvider::detach)
        activeMainScreenEnvironment = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately do not inspect event content. Screen capture is user-triggered.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        lastTextUndo = null
        deactivateMainScreenExecution()
        if (activeService === this) activeService = null
        (applicationContext as? NestroApplication)?.mikaVirtualDisplayController?.detachPhoneAgentEnvironment(this)
        super.onDestroy()
    }

    /** Read a window on the selected isolated display, never the user's foreground display. */
    fun captureVisibleText(displayId: Int): String = captureRoot(rootForDisplay(displayId), displaySize(displayId))

    private fun captureRoot(root: AccessibilityNodeInfo?, size: Point?): String {
        root ?: return "当前页面没有可读取的无障碍节点。"
        val lines = mutableListOf<String>()
        collect(root, lines, 0, size)
        val result = lines.take(120).joinToString("\n").ifBlank { "当前页面没有可读取的文本。" }
        audit("SCREEN_READ", "读取指定执行屏的可访问界面文本")
        return result
    }

    /** 目标执行屏的真实尺寸（像素），用于文本节点中心点与千分比坐标互换；取不到时快照不带坐标。 */
    private fun displaySize(displayId: Int): Point? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = getSystemService(DisplayManager::class.java) ?: return null
            val display = manager.getDisplay(displayId) ?: return null
            Point().also { display.getRealSize(it) }.takeIf { it.x > 0 && it.y > 0 }
        }.getOrNull()
    }

    private fun rootForDisplay(displayId: Int): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            // AccessibilityService.windows only returns the default display. Virtual displays
            // must be read through getWindowsOnAllDisplays(), then selected by display ID.
            // Android returns these windows in descending visual layer order. Preserve that
            // order so a focused but covered app window cannot outrank a visible modal dialog.
            getWindowsOnAllDisplays().get(displayId).orEmpty().asSequence()
                .mapNotNull { window -> runCatching { window.root }.getOrNull() }
                .firstOrNull()
        }.getOrNull()
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int, size: Point?) {
        if (depth > 12 || out.size >= 120) return
        if (node.isVisibleToUser && !node.isPassword) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val value = redactSensitiveText((text.ifBlank { desc }).take(180))
            if (value.isNotBlank()) {
                // 每行末尾标注该节点中心点的千分比坐标，供模型用 click_at 点按
                // 无文字的图标控件，或多个同名目标中指定的一个；取不到尺寸时退回纯文本。
                val annotated = if (size != null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val center = PhoneAgentCoordinates.centerPermille(
                        bounds.left, bounds.top, bounds.right, bounds.bottom, size.x, size.y
                    )
                    if (center != null) "$value ${PhoneAgentCoordinates.suffix(center.first, center.second)}" else value
                } else value
                out += annotated
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collect(child, out, depth + 1, size) }
            if (out.size >= 120) break
        }
    }

    fun hasValidGrant(): Boolean { val p = getSharedPreferences("nestro_local", MODE_PRIVATE); return p.getLong("phone_task_grant_until", 0L) > System.currentTimeMillis() || p.getBoolean("phone_single_grant", false) }
    fun isTaskStopped(taskId: String): Boolean =
        getSharedPreferences("nestro_local", MODE_PRIVATE).getBoolean(PhoneAgentWorkScheduler.stopKey(taskId), false)
    fun clearTaskStop(taskId: String) {
        getSharedPreferences("nestro_local", MODE_PRIVATE).edit()
            .remove(PhoneAgentWorkScheduler.stopKey(taskId)).apply()
    }
    fun requestTaskStop(taskId: String) {
        getSharedPreferences("nestro_local", MODE_PRIVATE).edit()
            .putBoolean(PhoneAgentWorkScheduler.stopKey(taskId), true).apply()
        audit("TASK_STOP", "用户请求停止任务 " + taskId.take(48))
    }

    fun clickVisibleText(displayId: Int, exactText: String): Boolean = clickVisibleText(rootForDisplay(displayId), exactText)

    private fun clickVisibleText(root: AccessibilityNodeInfo?, exactText: String): Boolean {
        val normalized = exactText.trim()
        require(normalized.isNotBlank() && normalized.length <= 80) { "目标文本无效" }
        require(!PhoneAgentRiskPolicy.requiresUserHandoffForClick(normalized)) { "该操作属于高风险操作，必须由用户本人完成确认" }
        require(!PhoneAgentRiskPolicy.containsSensitiveValue(normalized)) { "页面目标包含个人敏感信息，已阻止点击" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = root ?: return false
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

    /** 按千分比坐标点按目标执行屏：用于快照里有坐标、无文字的图标控件，
     * 或多个同名目标中点指定的一个。坐标必须来自当次屏幕快照，不得编造。 */
    fun clickAt(displayId: Int, xPermille: Int, yPermille: Int): Boolean {
        require(PhoneAgentCoordinates.isValid(xPermille, yPermille)) { "点击坐标无效" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootForDisplay(displayId) ?: return false
        val size = displaySize(displayId) ?: return false
        val x = PhoneAgentCoordinates.toPixels(xPermille, size.x)
        val y = PhoneAgentCoordinates.toPixels(yPermille, size.y)
        val target = findNodeAtPoint(root, x, y) ?: run {
            audit("CLICK_AT_BLOCKED", "目标点无可见控件")
            return false
        }
        if (target.isPassword) {
            audit("CLICK_AT_BLOCKED", "目标为密码字段")
            return false
        }
        // 点位节点的文字/描述仍走与 clickVisibleText 相同的风控：高风险与敏感目标拒绝点按。
        // 无文字的图标控件标签为空，风控对空标签返回 false，正好放行——这正是坐标点按要补的缺口。
        val label = (target.text?.toString().orEmpty().ifBlank { target.contentDescription?.toString().orEmpty() }).trim()
        require(!PhoneAgentRiskPolicy.requiresUserHandoffForClick(label)) { "该操作属于高风险操作，必须由用户本人完成确认" }
        require(!PhoneAgentRiskPolicy.containsSensitiveValue(label)) { "页面目标包含个人敏感信息，已阻止点击" }
        val success = dispatchTap(x.toFloat(), y.toFloat())
        consumeSingleGrantIfNeeded()
        audit("CLICK_AT", "坐标=[$xPermille,$yPermille]")
        return success
    }

    /** 目标点处最深的可见节点，仅用于点按前的安全核查；点按本身按坐标执行，不依赖节点文字。 */
    private fun findNodeAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNodeAtPoint(it, x, y) }?.let { return it }
        }
        return node
    }

    /** 经无障碍手势在指定像素点轻点一次。回调在主线程投递；主线程调用时等待会超时返回 false，不会死锁。 */
    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(gesture, callback, null)) return false
        latch.await(3, TimeUnit.SECONDS)
        return completed.get()
    }

    fun setTextOnVisibleField(displayId: Int, label: String, value: String): Boolean {
        val target = label.trim()
        require(target.isNotBlank() && target.length <= 80) { "输入框定位文本无效" }
        require(value.length <= 1000) { "输入内容过长" }
        require(!PhoneAgentRiskPolicy.containsSensitiveValue(value)) { "输入内容包含敏感信息，必须由用户本人处理" }
        require(
            !PhoneAgentRiskPolicy.isSensitiveField(target) &&
                !PhoneAgentRiskPolicy.containsSensitiveValue(target)
        ) { "密码、验证码及敏感字段必须由用户本人填写" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = rootForDisplay(displayId) ?: return false
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
                // 撤销快照与 captureVisibleText 用同一格式（含坐标标注），否则 undoLastTextEdit 的快照比对恒不成立。
                TextUndoRecord(target, previousValue, value, captureRoot(root, displaySize(displayId)))
            } else null
        }
        consumeSingleGrantIfNeeded()
        audit("SET_TEXT", "字段=$target，字符数=" + value.length)
        return success
    }

    fun canUndoLastTextEdit(): Boolean = lastTextUndo != null

    fun undoLastTextEdit(displayId: Int): Boolean {
        val record = lastTextUndo ?: return false
        if (!hasValidGrant() || PhoneAgentRiskPolicy.isSensitiveField(record.label) ||
            PhoneAgentRiskPolicy.redactSensitiveText(record.previousValue) != record.previousValue ||
            PhoneAgentRiskPolicy.redactSensitiveText(record.appliedValue) != record.appliedValue
        ) return false
        val currentSnapshot = captureVisibleText(displayId)
        if (currentSnapshot != record.screenSnapshot) return false
        val root = rootForDisplay(displayId) ?: return false
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

    fun scroll(displayId: Int, direction: String): Boolean = scroll(rootForDisplay(displayId), direction)

    private fun scroll(root: AccessibilityNodeInfo?, direction: String): Boolean {
        require(direction == "forward" || direction == "backward") { "滚动方向无效" }
        require(hasValidGrant()) { "没有有效的操作授权" }
        val root = root ?: return false
        val node = findScrollableNode(root) ?: return false
        val action = if (direction == "forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = node.performAction(action)
        consumeSingleGrantIfNeeded()
        audit("SCROLL", direction)
        return success
    }


    fun verifyVisibleText(displayId: Int, expected: String): Boolean = verifyVisibleText(rootForDisplay(displayId), expected)

    private fun verifyVisibleText(root: AccessibilityNodeInfo?, expected: String): Boolean {
        val needle = expected.trim()
        if (needle.isBlank()) return false
        val root = root ?: return false
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
    fun captureReadOnlySnapshot(displayId: Int, maxLines: Int = 120, maxChars: Int = 12000): String {
        return captureVisibleText(displayId).lines().take(maxLines).joinToString("\n").take(maxChars)
    }

    fun hasActiveWindow(displayId: Int): Boolean = rootForDisplay(displayId) != null
    fun downloadAsset(taskId: String, projectId: String, url: String, displayName: String, sourceUrl: String, licenseLabel: String): String? {
        require(hasValidGrant()) { "没有有效的操作授权" }
        require(url.startsWith("https://", ignoreCase = true) && PhoneAgentUrlPolicy.isSafePublicWebUrl(url) && !PhoneAgentRiskPolicy.containsSensitiveValue(url)) { "素材下载地址必须是安全的公开 HTTPS 直链" }
        require(sourceUrl.startsWith("https://", ignoreCase = true) && PhoneAgentUrlPolicy.isSafePublicWebUrl(sourceUrl) && !PhoneAgentRiskPolicy.containsSensitiveValue(sourceUrl)) { "素材来源页面必须是安全的公开 HTTPS 地址" }
        require(licenseLabel.isNotBlank() && licenseLabel.length <= 120) { "素材授权说明缺失或过长" }
        require(projectId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) { "当前项目标识无效" }
        require(displayName.length in 1..160 && displayName.matches(Regex("[A-Za-z0-9_ .()\\-\\u4e00-\\u9fff]+\\.(jpg|jpeg|png|webp|heic|mp4|mov|mkv|webm|mp3|m4a|wav|aac|ogg|flac)", RegexOption.IGNORE_CASE))) { "仅支持图片、视频和音频素材文件" }
        val extension = displayName.substringAfterLast('.').lowercase()
        val mime = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"
            "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"; "aac" -> "audio/aac"; "ogg" -> "audio/ogg"; else -> "audio/flac"
        }
        val manager = getSystemService(DownloadManager::class.java) ?: error("系统下载服务不可用")
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(displayName.take(100))
            setDescription("Nestro 正在下载并导入项目素材")
            setMimeType(mime)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(this@PhoneAgentService, Environment.DIRECTORY_DOWNLOADS, "nestro-$taskId-${System.currentTimeMillis()}.$extension")
        }
        val downloadId = manager.enqueue(request)
        try {
            val deadline = SystemClock.elapsedRealtime() + 120_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (isTaskStopped(taskId) || !hasValidGrant()) {
                    manager.remove(downloadId)
                    return null
                }
                manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        require(downloaded <= 512L * 1024 * 1024 && (total <= 0L || total <= 512L * 1024 * 1024)) { "素材超过 512 MB 下载上限" }
                        if (status == DownloadManager.STATUS_FAILED) {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            error("素材下载失败，系统错误码：$reason")
                        }
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uri = manager.getUriForDownloadedFile(downloadId) ?: error("下载完成但无法读取文件")
                            val asset = MediaProjectStore.importAsset(this, projectId, uri, displayName)
                            audit("ASSET_IMPORTED", "项目素材已导入：${asset.kind}，${asset.byteSize} 字节")
                            return "素材已下载并导入当前项目素材库：${asset.displayName}；素材 ID：${asset.id}；类型：${asset.kind}；大小：${asset.byteSize} 字节；来源页面：${sourceUrl.take(500)}；授权标记：${licenseLabel.take(120)}。"
                        }
                    }
                }
                Thread.sleep(500L)
            }
            error("素材下载超时，已停止等待")
        } finally {
            manager.remove(downloadId)
        }
    }

    fun activePackageName(displayId: Int): String? = rootForDisplay(displayId)?.packageName?.toString()

    fun screenTextChanged(displayId: Int, before: String): Boolean = captureVisibleText(displayId) != before

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
