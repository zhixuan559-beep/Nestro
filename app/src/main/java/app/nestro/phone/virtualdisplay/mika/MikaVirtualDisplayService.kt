/*
 * Nestro's narrow shell-UID virtual-display service, based on the virtual-display
 * and task-control approach in Mika (https://github.com/wangzan101/Mika), Apache-2.0.
 * Only virtual display, app launch, task movement and input injection are included.
 */
package app.nestro.phone.virtualdisplay.mika

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.util.Log
import com.wangzan101.mika.shell.IMikaShell
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs only inside the dedicated shell-UID helper process. */
internal class MikaVirtualDisplayService(
    private val allowedUid: Int,
) : IMikaShell.Stub() {
    private val context: Context = MikaShellContext.create()
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private var orientationWatch: DisplayOrientationWatch? = null
    private val requestedOrientations = ConcurrentHashMap<Int, Int>()
    private val launchLock = Any()
    private val keptTasks = HashMap<Int, String>()
    private val awakeTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nestro-display-awake").apply { isDaemon = true }
    }
    private val awake = DisplayAwake(::pokeDisplay) { runnable ->
        val future = awakeTimer.scheduleWithFixedDelay(runnable, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
        val cancel: () -> Unit = { future.cancel(false) }
        cancel
    }

    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    @SuppressLint("PrivateApi")
    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }
    private val mainScreenWatch by lazy {
        MainScreenWatch(
            taskManager = { taskManager },
            tasks = {
                allTasks().mapNotNull { item ->
                    runCatching {
                        val id = taskInfoClass.getField("taskId").getInt(item)
                        val displayId = taskInfoClass.getField("displayId").getInt(item)
                        val top = taskInfoClass.getField("topActivity").get(item) as? android.content.ComponentName
                        val base = taskInfoClass.getField("baseActivity").get(item) as? android.content.ComponentName
                        val userId = runCatching { taskInfoClass.getField("userId").getInt(item) }.getOrDefault(0)
                        MainScreenLog.Task(
                            taskId = id,
                            displayId = displayId,
                            packageName = top?.packageName ?: base?.packageName,
                            userId = userId,
                            topActivity = top?.className,
                            standard = true
                        )
                    }.getOrNull()
                }
            }
        )
    }

    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("Only the owning Nestro installation may use the virtual display helper")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        if (!valid(surface, width, height, dpi)) return -1
        val flags = displayFlags(trusted = true)
        val display = try {
            displayManager.createVirtualDisplay("nestro-agent", width, height, dpi, surface, flags)
        } catch (first: SecurityException) {
            Log.w(TAG, "Trusted display unavailable; retrying without trusted-only flags", first)
            runCatching {
                displayManager.createVirtualDisplay("nestro-agent", width, height, dpi, surface, displayFlags(trusted = false))
            }.getOrNull()
        } ?: return -1
        displays[display.display.displayId] = display
        ensureOrientationWatch()
        configureDisplay(display.display.displayId)
        val id = display.display.displayId
        if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        return id
    }

    override fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Boolean =
        synchronized(displays) {
            val display = displays[displayId] ?: return@synchronized false
            if (!valid(surface, width, height, dpi)) return@synchronized false
            val oldMetrics = android.util.DisplayMetrics().also { display.display.getRealMetrics(it) }
            val oldMode = display.display.mode
            val oldSurface = display.surface
            try {
                display.resize(width, height, dpi)
                display.surface = surface
                oldSurface?.release()
                true
            } catch (error: Exception) {
                runCatching {
                    display.resize(oldMode.physicalWidth, oldMode.physicalHeight, oldMetrics.densityDpi)
                    display.surface = oldSurface
                }
                Log.w(TAG, "resizeDisplay failed; previous display surface restored", error)
                false
            }
        }

    override fun releaseDisplay(displayId: Int) {
        runCatching { awake.stop(displayId) }
        val (display, watcher) = synchronized(displays) {
            displays.remove(displayId) to if (displays.isEmpty()) orientationWatch.also { orientationWatch = null } else null
        }
        runCatching { watcher?.close() }.onFailure { Log.w(TAG, "orientation watcher close failed", it) }
        if (watcher != null) requestedOrientations.clear()
        runCatching { display?.release() }.onFailure { Log.w(TAG, "virtual display release failed", it) }
    }

    private fun ensureOrientationWatch() {
        if (Build.VERSION.SDK_INT < 34 || synchronized(displays) { orientationWatch != null }) return
        val watcher = runCatching {
            DisplayOrientationWatch(
                taskManager = taskManager,
                active = { synchronized(displays) { displays.isNotEmpty() } },
                onRequestedOrientation = { taskId, orientation -> requestedOrientations[taskId] = orientation },
                onTaskRemoved = { taskId -> requestedOrientations.remove(taskId) },
                sync = ::syncDisplayOrientations
            )
        }.onFailure { Log.w(TAG, "Display orientation watcher unavailable", it) }.getOrNull() ?: return
        val spare = synchronized(displays) {
            if (orientationWatch == null && displays.isNotEmpty()) { orientationWatch = watcher; null } else watcher
        }
        spare?.close()
    }

    @SuppressLint("PrivateApi")
    private fun syncDisplayOrientations() {
        val own = synchronized(displays) { displays.toMap() }
        if (own.isEmpty()) return
        val tasksByDisplay = allTasks().groupBy { taskInfoClass.getField("displayId").getInt(it) }
        for ((id, virtualDisplay) in own) runCatching {
            val task = tasksByDisplay[id]?.firstOrNull() ?: return@runCatching
            val taskId = taskInfoClass.getField("taskId").getInt(task)
            val compat = runCatching { taskInfoClass.getField("appCompatTaskInfo").get(task) }.getOrNull() ?: task
            val type = compat.javaClass
            if (!runCatching { type.getField("topActivityBoundsLetterboxed").getBoolean(compat) }.getOrDefault(true)) {
                return@runCatching
            }
            val appWidth = runCatching { type.getField("topActivityLetterboxWidth").getInt(compat) }.getOrDefault(0)
            val appHeight = runCatching { type.getField("topActivityLetterboxHeight").getInt(compat) }.getOrDefault(0)
            val display = virtualDisplay.display
            val mode = display.mode
            val rotation = DisplayOrientation.rotation(
                mode.physicalWidth, mode.physicalHeight, display.rotation, appWidth, appHeight,
                requestedOrientations[taskId] ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ) ?: return@runCatching
            if (!owns(id)) return@runCatching
            val frozen = runCatching {
                val wm = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null)!!
                val method = wm.javaClass.methods.first { it.name == "freezeDisplayRotation" }
                if (method.parameterTypes.size == 3) method.invoke(wm, id, rotation, "nestro")
                else method.invoke(wm, id, rotation)
            }.isSuccess
            if (!frozen) {
                ProcessBuilder("wm", "user-rotation", "-d", "$id", "lock", "$rotation")
                    .redirectErrorStream(true).start().waitFor()
            }
        }.onFailure { Log.w(TAG, "Display orientation sync failed on display " + id, it) }
    }

    @SuppressLint("PrivateApi")
    private fun pokeDisplay(displayId: Int): Boolean = runCatching {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "power") as android.os.IBinder
        val power = Class.forName("android.os.IPowerManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
        val method = DisplayAwake.userActivityMethod(power.javaClass) ?: return false
        method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
        true
    }.getOrDefault(false)

    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    override fun startApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!owns(displayId) || !validPackage(packageName)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(packageName, displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot safely inspect existing app tasks; refusing launch", t)
            return START_ERROR
        }
        if (resumeKept(displayId, packageName)) return START_OK
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
        launch(displayId, intent)
    }

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int {
        if (!owns(displayId)) return START_ERROR
        val parsed = uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (parsed != null && parsed.scheme !in setOf("https", "http")) return START_INVALID_LINK
        if (!packageName.isNullOrBlank() && !validPackage(packageName)) return START_ERROR
        val intent = Intent(action?.takeIf { it.isNotBlank() } ?: Intent.ACTION_VIEW).apply {
            if (parsed != null) data = parsed
            if (!packageName.isNullOrBlank()) setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return synchronized(launchLock) {
            val resolvedPackage = runCatching {
                context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            }.getOrNull()
            if (resolvedPackage != null) {
                try {
                    if (inUseOnMainScreen(resolvedPackage)) return@synchronized START_IN_USE_ON_MAIN
                    if (onOtherOwnDisplay(resolvedPackage, displayId)) return@synchronized START_IN_USE_BY_OTHER
                } catch (t: Throwable) {
                    Log.e(TAG, "Cannot safely inspect target app tasks; refusing link launch", t)
                    return@synchronized START_ERROR
                }
                if (resumeKept(displayId, resolvedPackage)) return@synchronized START_OK
            }
            launch(displayId, intent)
        }
    }

    private fun launch(displayId: Int, intent: Intent): Int = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val result = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            start.invoke(
                taskManager, null, MikaShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, Process.myUid() / PER_USER_RANGE
            ) as Int
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable; falling back to am start", t)
            // Preserve implicit intents in the shell fallback. A VIEW intent often has no
            // explicit component; requiring one here made URL launches fail on devices where
            // the hidden startActivityAsUser overload is unavailable.
            val command = buildList {
                add("am"); add("start"); add("--display"); add(displayId.toString())
                intent.action?.let { add("-a"); add(it) }
                intent.data?.let { add("-d"); add(it.toString()) }
                intent.`package`?.let { add("-p"); add(it) }
                intent.component?.let { add("-n"); add(it.flattenToShortString()) }
            }
            if (command.size <= 4) return START_NOT_FOUND
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().take(2_000) }
            if (process.waitFor() == 0) return START_OK
            Log.w(TAG, "am start fallback failed: " + output.take(500))
            return START_ERROR
        }
        if (result >= 0) START_OK else START_ERROR
    } catch (t: Throwable) {
        Log.w(TAG, "Unable to launch app on isolated display", t)
        START_ERROR
    }

    override fun topTaskId(displayId: Int): Int = runCatching {
        tasks(displayId).firstOrNull()?.let { taskId(it) } ?: -1
    }.getOrDefault(-1)

    override fun displayOfTask(taskId: Int): Int = runCatching {
        task(taskId)?.let { taskInfoClass.getField("displayId").getInt(it) } ?: -1
    }.getOrDefault(-1)

    override fun packageOfTask(taskId: Int): String? = runCatching {
        val item = task(taskId) ?: return@runCatching null
        listOf("topActivity", "baseActivity").firstNotNullOfOrNull { name ->
            (taskInfoClass.getField(name).get(item) as? android.content.ComponentName)?.packageName
        }
    }.getOrNull()

    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        if (displayId != Display.DEFAULT_DISPLAY && !owns(displayId)) return false
        val commandSucceeded = try {
            taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
                .invoke(taskManager, taskId, displayId)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "moveRootTaskToDisplay unavailable; falling back to am", t)
            runCatching {
                ProcessBuilder("am", "display", "move-stack", "$taskId", "$displayId")
                    .redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
        }
        // The framework method is void and may return normally when the task ID no longer exists.
        // Confirm the task actually landed on the requested display before reporting success.
        return commandSucceeded && displayOfTask(taskId) == displayId
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!owns(displayId) || keyCode !in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL)) return false
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return try {
            val downSent = inject(down, displayId)
            // Always send key-up even if key-down injection reports failure, avoiding a stuck key.
            val upSent = inject(up, displayId)
            downSent && upSent
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        injectStroke(displayId, intArrayOf(x, x), intArrayOf(y, y), longArrayOf(0L, 60L))

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!owns(displayId) || xs.size < 2 || xs.size != ys.size || xs.size != times.size || xs.size > 1024) return false
        if (times.first() != 0L || times.last() > 10_000L || times.zipWithNext().any { it.first > it.second }) return false
        val downTime = android.os.SystemClock.uptimeMillis()
        var ok = true
        var lastX = xs.first()
        var lastY = ys.first()
        try {
            for (i in xs.indices) {
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; xs.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(downTime, downTime + times[i], action, xs[i].toFloat(), ys[i].toFloat(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    ok = inject(event, displayId) && ok
                } finally {
                    event.recycle()
                }
                lastX = xs[i]
                lastY = ys[i]
                if (i < xs.lastIndex && times[i + 1] > times[i]) Thread.sleep(times[i + 1] - times[i])
            }
            return ok
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            // A cancelled gesture must not leave the virtual touchscreen in a pressed state.
            val cancel = MotionEvent.obtain(
                downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
                lastX.toFloat(), lastY.toFloat(), 0
            )
            cancel.source = InputDevice.SOURCE_TOUCHSCREEN
            try { inject(cancel, displayId) } finally { cancel.recycle() }
            return false
        }
    }

    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
        if (!owns(displayId) || text.length > 4000 || !text.all { it.code in 32..126 }) return 0
        if (displayOfTask(taskId) != displayId || packageOfTask(taskId) != packageName) return 0
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return 0
        var sent = true
        events.forEach { event ->
            try {
                sent = inject(event, displayId) && sent
            } finally {
                event.recycle()
            }
        }
        return if (sent) 1 else 2
    }

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?) {
        if (!owns(displayId)) return
        val keepSet = keep.orEmpty().toSet()
        val closeSet = close.orEmpty().toSet()
        tasks(displayId).forEach { item ->
            val id = runCatching { taskInfoClass.getField("taskId").getInt(item) }.getOrDefault(-1)
            if (id < 0) return@forEach
            val pkg = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { field ->
                runCatching { (taskInfoClass.getField(field).get(item) as? android.content.ComponentName)?.packageName }.getOrNull()
            }
            when {
                pkg != null && pkg in keepSet && moveTask(id, Display.DEFAULT_DISPLAY) ->
                    synchronized(keptTasks) { keptTasks[id] = pkg }
                pkg != null && pkg in closeSet -> removeTask(id)
            }
        }
        releaseDisplay(displayId)
    }

    @SuppressLint("PrivateApi")
    private fun removeTask(taskId: Int): Boolean {
        val invoked = runCatching {
            taskManager.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(taskManager)
            true
        }.getOrDefault(false)
        // Some framework/OEM variants expose a void method; verify removal instead of
        // treating a normal Binder return as proof that the task disappeared.
        return invoked && task(taskId) == null
    }

    override fun watchMainScreen(windowMs: Int): Int = mainScreenWatch.watch(windowMs)

    override fun mainArrivals(sinceUptimeMs: Long): Bundle = mainScreenWatch.arrivals(sinceUptimeMs)

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun releaseAll() {
        displays.keys.toList().forEach { displayId ->
            runCatching { releaseDisplay(displayId) }
                .onFailure { Log.w(TAG, "releaseAll failed for display " + displayId, it) }
        }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: android.view.InputEvent, displayId: Int): Boolean = runCatching {
        event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        manager.javaClass.methods.first { it.name == "injectInputEvent" && it.parameterTypes.size == 2 }
            .invoke(manager, event, 0) as? Boolean ?: false
    }.getOrDefault(false)

    @SuppressLint("PrivateApi")
    private fun configureDisplay(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, WINDOWING_MODE_FULLSCREEN)
        }.onFailure {
            runCatching {
                ProcessBuilder("wm", "set-display-windowing-mode", "-d", "$displayId", "$WINDOWING_MODE_FULLSCREEN")
                    .redirectErrorStream(true).start().waitFor()
            }
        }
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "Unable to isolate IME policy for display " + displayId, it) }
    }

    private fun owns(displayId: Int) = displays.containsKey(displayId)
    private fun valid(surface: Surface, width: Int, height: Int, dpi: Int) =
        surface.isValid && width in 1..4096 && height in 1..4096 && dpi in 72..1000
    private fun validPackage(name: String) = name.length <= 255 && name.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))

    private fun tasks(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val result = if (withDisplay != null) withDisplay.invoke(taskManager, 100, false, false, displayId)
            else methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, 100, false, false)
        val displayField = taskInfoClass.getField("displayId")
        return (result as? List<*>)?.filterNotNull()?.filter { displayField.getInt(it) == displayId }.orEmpty()
    }

    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val result = if (withDisplay != null) withDisplay.invoke(taskManager, 100, false, false, Display.INVALID_DISPLAY)
            else methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, 100, false, false)
        return (result as? List<*>)?.filterNotNull().orEmpty()
    }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull { field ->
        (taskInfoClass.getField(field).get(task) as? android.content.ComponentName)?.packageName
    }

    private fun inUseOnMainScreen(packageName: String): Boolean {
        val visibleField = runCatching { taskInfoClass.getField("isVisible") }.getOrNull()
        return tasks(Display.DEFAULT_DISPLAY).withIndex().any { (index, item) ->
            packageName in packagesOf(item) && (visibleField?.getBoolean(item) ?: (index == 0))
        }
    }

    private fun onOtherOwnDisplay(packageName: String, displayId: Int): Boolean {
        val others = synchronized(displays) { displays.keys.toSet() } - displayId
        if (others.isEmpty()) return false
        val displayField = taskInfoClass.getField("displayId")
        return allTasks().any { item ->
            displayField.getInt(item) in others && packageName in packagesOf(item)
        }
    }

    private fun resumeKept(displayId: Int, packageName: String): Boolean {
        val candidate = synchronized(keptTasks) { keptTasks.entries.firstOrNull { it.value == packageName }?.key }
            ?: return false
        if (displayOfTask(candidate) != Display.DEFAULT_DISPLAY) {
            synchronized(keptTasks) { keptTasks.remove(candidate) }
            return false
        }
        if (!moveTask(candidate, displayId)) return false
        synchronized(keptTasks) { keptTasks.remove(candidate) }
        return true
    }

    private fun task(taskId: Int): Any? =
        allTasks().firstOrNull { taskInfoClass.getField("taskId").getInt(it) == taskId }

    private val taskInfoClass: Class<*> get() = Class.forName("android.app.TaskInfo")

    private fun displayFlags(trusted: Boolean): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
            if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        }
        if (Build.VERSION.SDK_INT >= 34 && trusted) flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        return flags
    }

    companion object {
        private const val TAG = "NestroVirtualDisplay"
        private const val START_OK = 0
        private const val START_IN_USE_ON_MAIN = 1
        private const val START_NOT_FOUND = 2
        private const val START_ERROR = 3
        private const val START_INVALID_LINK = 6
        private const val START_IN_USE_BY_OTHER = 7
        private const val PER_USER_RANGE = 100_000
        private const val WINDOWING_MODE_FULLSCREEN = 1
        private const val IME_POLICY_LOCAL = 0
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }
}
