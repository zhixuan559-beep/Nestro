package app.nestro.phone.virtualdisplay.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayGeometryTest {
    @Test
    fun viewportCapsLongSideAndPreservesAspectRatio() {
        val viewport = phoneViewport(width = 1440, height = 3200, deviceDpi = 480)

        assertTrue(maxOf(viewport.width, viewport.height) <= 2048)
        assertEquals(1440f / 3200f, viewport.width.toFloat() / viewport.height.toFloat(), 0.002f)
        assertTrue(viewport.dpi in 72..1000)
    }

    @Test
    fun viewportDoesNotUpscaleSmallerDisplays() {
        val viewport = phoneViewport(width = 720, height = 1280, deviceDpi = 320)

        assertEquals(720, viewport.width)
        assertEquals(1280, viewport.height)
        assertEquals(320, viewport.dpi)
    }

    @Test
    fun quarterTurnUsesLogicalDimensionsForPreview() {
        val geometry = DisplayFrameGeometry(bufferWidth = 1080, bufferHeight = 2400, rotation = 1)

        assertEquals(2400, geometry.width)
        assertEquals(1080, geometry.height)
        val preview = geometry.previewSize(maxWidth = 360)
        assertEquals(360, preview.first)
        assertEquals(162, preview.second)
    }

    @Test
    fun resizePreservesCurrentRotationAndRequestedLogicalSize() {
        val geometry = DisplayFrameGeometry(bufferWidth = 1080, bufferHeight = 2400, rotation = 1)
        val resized = geometry.resized(width = 720, height = 1280)

        assertEquals(1, resized.rotation)
        assertEquals(720, resized.width)
        assertEquals(1280, resized.height)
        assertEquals(1280, resized.bufferWidth)
        assertEquals(720, resized.bufferHeight)
    }
}
