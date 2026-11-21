package app.nestro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun drawerWidthAdaptsAcrossCompactPhoneTabletAndExpandedWindows() {
        assertEquals(248, adaptiveDrawerWidthDp(320))
        assertEquals(280, adaptiveDrawerWidthDp(390))
        assertEquals(280, adaptiveDrawerWidthDp(599))
        assertEquals(320, adaptiveDrawerWidthDp(600))
        assertEquals(360, adaptiveDrawerWidthDp(840))
    }
}
