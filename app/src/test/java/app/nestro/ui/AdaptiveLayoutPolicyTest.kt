package app.nestro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun drawerWidthAdaptsAcrossCompactPhoneTabletAndExpandedWindows() {
        assertEquals(248, adaptiveDrawerWidthDp(320))
        assertEquals(280, adaptiveDrawerWidthDp(390))
        assertEquals(280, adaptiveDrawerWidthDp(599))
        assertEquals(240, adaptiveDrawerWidthDp(600))
        assertEquals(280, adaptiveDrawerWidthDp(700))
        assertEquals(320, adaptiveDrawerWidthDp(839))
        assertEquals(320, adaptiveDrawerWidthDp(840))
        assertEquals(360, adaptiveDrawerWidthDp(1200))
    }

    @Test
    fun contentSpacingScalesWithAvailableWidth() {
        assertEquals(12, adaptiveContentPaddingDp(320))
        assertEquals(16, adaptiveContentPaddingDp(390))
        assertEquals(24, adaptiveContentPaddingDp(700))
        assertEquals(32, adaptiveContentPaddingDp(1000))
    }
}
