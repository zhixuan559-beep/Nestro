/*
 * Nestro adapter boundary for the Mika-derived virtual display backend.
 * Mika source: https://github.com/wangzan101/Mika (Apache-2.0).
 */
package app.nestro.phone.virtualdisplay.mika

import android.view.Surface

/** Narrow Binder contract: only operations required for isolated phone execution are exposed. */
interface MikaVirtualDisplayShell {
    fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int
    fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Boolean
    fun releaseDisplay(displayId: Int)
}
