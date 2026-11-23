package app.nestro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NestroLayoutPolicyTest {
    @Test
    fun keepsDrawerWidthContinuousAtCompactAndStandardBreakpoints() {
        assertEquals(248, adaptiveDrawerWidthDp(359))
        assertEquals(256, adaptiveDrawerWidthDp(360))
        assertEquals(280, adaptiveDrawerWidthDp(599))
    }

    @Test
    fun switchesDrawerSizingAtExpandedBreakpoint() {
        assertEquals(240, adaptiveDrawerWidthDp(600))
        assertEquals(280, adaptiveDrawerWidthDp(700))
        assertEquals(320, adaptiveDrawerWidthDp(840))
        assertEquals(360, adaptiveDrawerWidthDp(1200))
    }

    @Test
    fun contentPaddingChangesAtWindowSizeBreakpoints() {
        assertEquals(12, adaptiveContentPaddingDp(359))
        assertEquals(16, adaptiveContentPaddingDp(360))
        assertEquals(24, adaptiveContentPaddingDp(600))
        assertEquals(32, adaptiveContentPaddingDp(840))
    }
}
