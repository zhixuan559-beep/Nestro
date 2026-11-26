package app.nestro.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentCoordinatesTest {
    @Test
    fun centerPermilleUsesNodeCenterRelativeToDisplay() {
        // 1080x1920 屏上一个 540x480 的节点：中心 (270, 240) -> (250, 125)
        assertEquals(250 to 125, PhoneAgentCoordinates.centerPermille(0, 0, 540, 480, 1080, 1920))
        // 全屏节点中心即 (500, 500)
        assertEquals(500 to 500, PhoneAgentCoordinates.centerPermille(0, 0, 1080, 1920, 1080, 1920))
    }

    @Test
    fun centerPermilleRejectsEmptyBoundsOrInvalidDisplaySize() {
        assertNull(PhoneAgentCoordinates.centerPermille(100, 100, 100, 200, 1080, 1920))
        assertNull(PhoneAgentCoordinates.centerPermille(0, 0, 100, 100, 0, 1920))
        assertNull(PhoneAgentCoordinates.centerPermille(0, 0, 100, 100, 1080, 0))
    }

    @Test
    fun centerPermilleClampsToDisplayRange() {
        // 中心 (1140, 1960) 超出 1080x1920 的屏，钳制到 (1000, 1000)
        assertEquals(1000 to 1000, PhoneAgentCoordinates.centerPermille(1080, 1920, 1200, 2000, 1080, 1920))
    }

    @Test
    fun toPixelsConvertsPermilleToDisplayPixels() {
        assertEquals(540, PhoneAgentCoordinates.toPixels(500, 1080))
        assertEquals(0, PhoneAgentCoordinates.toPixels(0, 1080))
        assertEquals(1920, PhoneAgentCoordinates.toPixels(1000, 1920))
    }

    @Test
    fun toPixelsRejectsOutOfRangeCoordinateOrSize() {
        try {
            PhoneAgentCoordinates.toPixels(1001, 1080)
            org.junit.Assert.fail("permille above 1000 must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            PhoneAgentCoordinates.toPixels(500, 0)
            org.junit.Assert.fail("zero display size must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun validityCoversFullPermilleRange() {
        assertTrue(PhoneAgentCoordinates.isValid(0, 0))
        assertTrue(PhoneAgentCoordinates.isValid(1000, 1000))
        assertFalse(PhoneAgentCoordinates.isValid(1001, 0))
        assertFalse(PhoneAgentCoordinates.isValid(0, -1))
    }

    @Test
    fun suffixFormatsSnapshotCoordinate() {
        assertEquals("[x=540,y=960]", PhoneAgentCoordinates.suffix(540, 960))
    }
}
