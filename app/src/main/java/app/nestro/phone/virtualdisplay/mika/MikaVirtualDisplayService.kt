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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.util.Log
import com.wangzan101.mika.shell.IMikaShell
import java.util.concurrent.ConcurrentHashMap

/** Runs only inside the dedicated shell-UID helper process. */
internal class MikaVirtualDisplayService(
    private val allowedUid: Int,
) : IMikaShell.Stub() {
    private val context: Context = MikaShellContext.create()
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    @SuppressLint("PrivateApi")
    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
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
        return display.display.displayId
    }

    override fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
        val display = displays[displayId] ?: return false
        if (!valid(surface, width, height, dpi)) return false
        return runCatching {
            display.resize(width, height, dpi)
            display.surface = surface
            true
        }.getOrDefault(false)
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    override fun startApp(displayId: Int, packageName: String): Int {
        if (!owns(displayId) || !validPackage(packageName)) return START_ERROR
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
        return launch(displayId, intent)
    }

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int {
        if (!owns(displayId)) return START_ERROR
        val parsed = uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (parsed != null && parsed.scheme !in setOf("https", "http")) return START_INVALID_LINK
        val intent = Intent(action?.takeIf { it.isNotBlank() } ?: Intent.ACTION_VIEW).apply {
            if (parsed != null) data = parsed
            if (!packageName.isNullOrBlank() && validPackage(packageName)) setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(displayId, intent)
    }

    private fun launch(displayId: Int, intent: Intent): Int = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(intent, options.toBundle())
        START_OK
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
        return runCatching {
            taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
                .invoke(taskManager, taskId, displayId)
            true
        }.getOrElse {
            runCatching {
                val process = ProcessBuilder("am", "display", "move-stack", "$taskId", "$displayId")
                    .redirectErrorStream(true).start()
                process.waitFor() == 0
            }.getOrDefault(false)
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!owns(displayId) || keyCode !in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL)) return false
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return inject(down) && inject(up)
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        injectStroke(displayId, intArrayOf(x, x), intArrayOf(y, y), longArrayOf(0L, 60L))

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!owns(displayId) || xs.size < 2 || xs.size != ys.size || xs.size != times.size || xs.size > 1024) return false
        if (times.first() != 0L || times.last() > 10_000L || times.zipWithNext().any { it.first > it.second }) return false
        val downTime = android.os.SystemClock.uptimeMillis()
        var ok = true
        for (i in xs.indices) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; xs.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(downTime, downTime + times[i], action, xs[i].toFloat(), ys[i].toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            runCatching {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            }
            ok = inject(event) && ok
            event.recycle()
            if (i < xs.lastIndex && times[i + 1] > times[i]) Thread.sleep(times[i + 1] - times[i])
        }
        return ok
    }

    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
        if (!owns(displayId) || text.length > 4000 || !text.all { it.code in 32..126 }) return 0
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return 0
        var sent = true
        events.forEach { event -> sent = inject(event) && sent }
        return if (sent) 1 else 2
    }

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?) {
        releaseDisplay(displayId)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun releaseAll() {
        displays.keys.toList().forEach(::releaseDisplay)
    }

    private fun inject(event: android.view.InputEvent): Boolean = runCatching {
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        manager.javaClass.methods.first { it.name == "injectInputEvent" && it.parameterTypes.size == 2 }
            .invoke(manager, event, 0) as? Boolean ?: false
    }.getOrDefault(false)

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

    private fun task(taskId: Int): Any? {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val result = methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, 100, false, false) as? List<*>
        return result?.filterNotNull()?.firstOrNull { taskInfoClass.getField("taskId").getInt(it) == taskId }
    }

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
        private const val START_NOT_FOUND = 2
        private const val START_ERROR = 3
        private const val START_INVALID_LINK = 6
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
