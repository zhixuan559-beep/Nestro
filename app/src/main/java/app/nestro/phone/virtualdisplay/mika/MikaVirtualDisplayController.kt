/*
 * Nestro lifecycle/controller for the Mika-derived isolated phone display.
 * Preview scaling is deliberately independent from virtual-display resolution.
 */
package app.nestro.phone.virtualdisplay.mika

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import app.nestro.phone.PhoneAgentExecutionEnvironmentProvider
import app.nestro.phone.PhoneAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

class MikaVirtualDisplayController(context: Context) {
    private val app = context.applicationContext
    private val adb = MikaAdbManager(app)
    private val displayManager = app.getSystemService(DisplayManager::class.java)
    @Volatile private var activeSession: Session? = null
    @Volatile private var activeEnvironment: MikaPhoneAgentExecutionEnvironment? = null
    @Volatile private var activeBridgeService: PhoneAgentService? = null

    val helperConnected get() = adb.shell.value != null
    val virtualExecutionPreferred get() = app.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        .getBoolean("phone_virtual_execution_preferred", false)
    fun helperState() = adb.shell

    suspend fun pair(pairingPort: Int, pairingCode: String) = adb.pair(pairingPort, pairingCode)
    suspend fun findPairingPorts(timeoutMs: Long = 3_000L) = adb.findPairingPorts(timeoutMs)
    suspend fun findConnectionPorts(timeoutMs: Long = 3_000L) = adb.findConnectionPorts(timeoutMs)
    suspend fun connectHelper() = adb.launchHelper()

    suspend fun createSession(width: Int, height: Int, densityDpi: Int): Session = withContext(Dispatchers.IO) {
        require(width > 0 && height > 0 && densityDpi > 0)
        val remote = adb.adapterOrNull() ?: throw IllegalStateException("无线调试助手尚未连接")
        // 先记下桥接服务：close() 会摘除旧环境并清空引用；新会话就绪后要按原偏好重挂，
        // 否则“服务先连、会话后建 / 会话重建”的时序下环境永远挂不上，任务一直暂停。
        val bridgeService = activeBridgeService
        activeSession?.close()
        val viewport = phoneViewport(width, height, densityDpi)
        val display = MikaAgentDisplay(remote, viewport.width, viewport.height, viewport.dpi, displayManager)
        val session = Session(remote, display)
        activeSession = session
        // 会话（重）建后立即重挂：此前已选虚拟执行且桥接在位时才挂；不碰主屏兜底，绝不自动接管主屏。
        if (virtualExecutionPreferred && bridgeService != null) {
            activatePhoneAgent(bridgeService)
        }
        session
    }

    fun currentSession(): Session? = activeSession

    fun activatePhoneAgent(service: PhoneAgentService): Boolean {
        // 先记住桥接服务：无会话时本次挂载失败，但引用要留给 createSession 在会话就绪后重挂；
        // 否则“服务先连、会话后建”的时序下环境永远挂不上。
        activeBridgeService = service
        val session = activeSession ?: return false
        val preferenceSaved = app.getSharedPreferences("nestro_local", Context.MODE_PRIVATE).edit()
            .putBoolean("phone_virtual_execution_preferred", true).commit()
        if (!preferenceSaved) return false
        val environment = MikaPhoneAgentExecutionEnvironment(service, session)
        activeEnvironment = environment
        PhoneAgentExecutionEnvironmentProvider.attach(environment)
        return true
    }


    fun detachPhoneAgentEnvironment(service: PhoneAgentService) {
        if (activeBridgeService !== service) return
        activeEnvironment?.let(PhoneAgentExecutionEnvironmentProvider::detach)
        activeEnvironment = null
        activeBridgeService = null
    }

    inner class Session internal constructor(
        private val shell: MikaVirtualDisplayShell,
        val display: MikaAgentDisplay,
    ) : Closeable {
        val displayId get() = display.displayId
        val logicalWidth get() = display.width
        val logicalHeight get() = display.height
        val frames get() = display.frames
        val pipInput: MikaPipInput = object : MikaPipInput {
            override suspend fun tap(fx: Float, fy: Float) { this@Session.tap(fx, fy) }
            override suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
                this@Session.swipe(points, durationMs)
            }
            override suspend fun back() { this@Session.back() }
        }

        suspend fun launchApp(packageName: String): Int = withContext(Dispatchers.IO) {
            shell.startApp(displayId, packageName)
        }

        suspend fun openUrl(rawUrl: String): Int = withContext(Dispatchers.IO) {
            val uri = Uri.parse(rawUrl)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
            shell.startView(displayId, android.content.Intent.ACTION_VIEW, uri.toString(), null)
        }

        suspend fun tap(fx: Float, fy: Float): Boolean = withContext(Dispatchers.IO) {
            if (fx !in 0f..1f || fy !in 0f..1f) return@withContext false
            shell.injectTap(displayId, (fx * logicalWidth).toInt().coerceIn(0, logicalWidth - 1),
                (fy * logicalHeight).toInt().coerceIn(0, logicalHeight - 1))
        }

        suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
            if (points.size < 2 || points.size > 1024 || durationMs !in 1L..10_000L) return@withContext false
            if (points.any { it.first !in 0f..1f || it.second !in 0f..1f }) return@withContext false
            val xs = IntArray(points.size) { (points[it].first * logicalWidth).toInt().coerceIn(0, logicalWidth - 1) }
            val ys = IntArray(points.size) { (points[it].second * logicalHeight).toInt().coerceIn(0, logicalHeight - 1) }
            val times = LongArray(points.size) { durationMs * it / (points.size - 1) }
            shell.injectStroke(displayId, xs, ys, times)
        }

        suspend fun back(): Boolean = withContext(Dispatchers.IO) {
            shell.injectKey(displayId, android.view.KeyEvent.KEYCODE_BACK)
        }

        suspend fun topTaskId(): Int = withContext(Dispatchers.IO) { shell.topTaskId(displayId) }

        suspend fun topTaskPackage(): String? = withContext(Dispatchers.IO) {
            val taskId = shell.topTaskId(displayId)
            if (taskId < 0) null else shell.packageOfTask(taskId)
        }

        suspend fun moveTaskToMain(taskId: Int): Boolean = withContext(Dispatchers.IO) {
            taskId >= 0 && shell.moveTask(taskId, Display.DEFAULT_DISPLAY)
        }

        suspend fun moveTopTaskToMain(): Boolean = withContext(Dispatchers.IO) {
            val taskId = shell.topTaskId(displayId)
            taskId >= 0 && shell.moveTask(taskId, Display.DEFAULT_DISPLAY)
        }

        suspend fun moveTaskBack(taskId: Int): Boolean = withContext(Dispatchers.IO) {
            shell.moveTask(taskId, displayId)
        }

        suspend fun watchMainScreen(windowMs: Int): Int = withContext(Dispatchers.IO) {
            shell.watchMainScreen(windowMs.coerceIn(0, 30_000))
        }

        suspend fun mainScreenArrivals(sinceUptimeMs: Long): android.os.Bundle = withContext(Dispatchers.IO) {
            shell.mainArrivals(sinceUptimeMs)
        }

        suspend fun resizeVirtualDisplay(width: Int, height: Int, densityDpi: Int): Boolean {
            val viewport = phoneViewport(width, height, densityDpi)
            return display.resize(viewport.width, viewport.height, viewport.dpi)
        }

        override fun close() {
            // A stale Session may be closed after a replacement session has become active.
            // It must release only its own display, never detach the replacement session's
            // Phone Agent environment.
            val isCurrentSession = activeSession === this
            if (isCurrentSession) {
                activeSession = null
                activeEnvironment?.let(PhoneAgentExecutionEnvironmentProvider::detach)
                activeEnvironment = null
                activeBridgeService = null
            }
            // 主屏兜底只在用户明确允许后由界面手动启用，这里不自动挂载；
            // 任务暂停，直至虚拟屏恢复或用户手动启用主屏执行。
            // MikaAgentDisplay.release() already releases the remote display; calling
            // finishDisplay here first would send a duplicate Binder release.
            display.release()
        }
    }
}
