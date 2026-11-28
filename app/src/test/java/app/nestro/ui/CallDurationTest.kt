package app.nestro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 改版第 e 步：通话时长 mm:ss 格式。 */
class CallDurationTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("00:00", formatCallDuration(0))
        assertEquals("00:07", formatCallDuration(7))
        assertEquals("02:31", formatCallDuration(151))
        assertEquals("10:00", formatCallDuration(600))
    }

    @Test
    fun negativeSecondsClampToZero() {
        assertEquals("00:00", formatCallDuration(-5))
    }
}
