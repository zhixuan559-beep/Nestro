/*
 * Nestro adapter boundary for the Mika-derived virtual display backend.
 * Mika: https://github.com/wangzan101/Mika (Apache-2.0).
 */
package app.nestro.phone.virtualdisplay.mika

import android.view.Surface
import com.wangzan101.mika.shell.IMikaShell

/** Narrow execution API shared by the renderer and Phone Agent. */
interface MikaVirtualDisplayShell {
    fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int
    fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Boolean
    fun releaseDisplay(displayId: Int)
    fun startApp(displayId: Int, packageName: String): Int
    fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int
    fun topTaskId(displayId: Int): Int
    fun moveTask(taskId: Int, displayId: Int): Boolean
    fun displayOfTask(taskId: Int): Int
    fun packageOfTask(taskId: Int): String?
    fun injectKey(displayId: Int, keyCode: Int): Boolean
    fun injectTap(displayId: Int, x: Int, y: Int): Boolean
    fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean
    fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int
    fun finishDisplay(displayId: Int, keep: Array<String> = emptyArray(), close: Array<String> = emptyArray())
}

/** Thin adapter over the original Mika Binder transaction protocol. */
class MikaAidlShellAdapter(private val remote: IMikaShell) : MikaVirtualDisplayShell {
    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int) =
        remote.createDisplay(surface, width, height, dpi)
    override fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int) =
        remote.resizeDisplay(displayId, surface, width, height, dpi)
    override fun releaseDisplay(displayId: Int) = remote.releaseDisplay(displayId)
    override fun startApp(displayId: Int, packageName: String) = remote.startApp(displayId, packageName)
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?) =
        remote.startView(displayId, action, uri, packageName)
    override fun topTaskId(displayId: Int) = remote.topTaskId(displayId)
    override fun moveTask(taskId: Int, displayId: Int) = remote.moveTask(taskId, displayId)
    override fun displayOfTask(taskId: Int) = remote.displayOfTask(taskId)
    override fun packageOfTask(taskId: Int) = remote.packageOfTask(taskId)
    override fun injectKey(displayId: Int, keyCode: Int) = remote.injectKey(displayId, keyCode)
    override fun injectTap(displayId: Int, x: Int, y: Int) = remote.injectTap(displayId, x, y)
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray) =
        remote.injectStroke(displayId, xs, ys, times)
    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String) =
        remote.injectAsciiText(displayId, taskId, packageName, text)
    override fun finishDisplay(displayId: Int, keep: Array<String>, close: Array<String>) =
        remote.finishDisplay(displayId, keep, close)
}
