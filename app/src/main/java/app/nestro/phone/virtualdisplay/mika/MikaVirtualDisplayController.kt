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
    @Volatile private var activeAccessibilityService: PhoneAgentService? = null

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
        activeSession?.close()
        val viewport = phoneViewport(width, height, densityDpi)
        val display = MikaAgentDisplay(remote, viewport.width, viewport.height, viewport.dpi, displayManager)
        Session(remote, display).also { activeSession = it }
    }

    fun currentSession(): Session? = activeSession

    fun activatePhoneAgent(service: PhoneAgentService): Boolean {
        val session = activeSession ?: return false
        activeAccessibilityService = service
        val preferenceSaved = app.getSharedPreferences("nestro_local", Context.MODE_PRIVATE).edit()
            .putBoolean("phone_virtual_execution_preferred", true).commit()
        if (!preferenceSaved) return false
        val environment = MikaPhoneAgentExecutionEnvironment(service, session)
        activeEnvironment = environment
        PhoneAgentExecutionEnvironmentProvider.attach(environment)
        return true
    }

    fun restoreAccessibilityEnvironment(service: PhoneAgentService): Boolean {
        val preferenceSaved = app.getSharedPreferences("nestro_local", Context.MODE_PRIVATE).edit()
            .putBoolean("phone_virtual_execution_preferred", false).commit()
        if (!preferenceSaved) return false
        activeEnvironment?.let(PhoneAgentExecutionEnvironmentProvider::detach)
        activeEnvironment = null
        activeAccessibilityService = service
        PhoneAgentExecutionEnvironmentProvider.attach(service)
        return true
    }

    fun detachPhoneAgentEnvironment(service: PhoneAgentService) {
        if (activeAccessibilityService !== service) return
        activeEnvironment?.let(PhoneAgentExecutionEnvironmentProvider::detach)
        activeEnvironment = null
        activeAccessibilityService = null
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
            if (activeSession === this) activeSession = null
            activeEnvironment?.let(PhoneAgentExecutionEnvironmentProvider::detach)
            activeEnvironment = null
            // Closing the isolated session must not silently fall back to the user's real screen.
            // The user can explicitly restore main-screen execution from the Phone Agent page.
            runCatching { shell.finishDisplay(displayId) }
            display.release()
        }
    }
}
