package app.nestro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NestroLayoutPolicyTest {
    @Test
    fun choosesCompactDrawerForNarrowPhones() {
        assertEquals(252, adaptiveDrawerWidthDp(320))
        assertEquals(252, adaptiveDrawerWidthDp(359))
    }

    @Test
    fun keepsStandardDrawerForRegularPhoneWindows() {
        assertEquals(286, adaptiveDrawerWidthDp(360))
        assertEquals(286, adaptiveDrawerWidthDp(839))
    }

    @Test
    fun expandsDrawerForTabletAndUnfoldedWindows() {
        assertEquals(320, adaptiveDrawerWidthDp(840))
        assertEquals(320, adaptiveDrawerWidthDp(1200))
    }
}
