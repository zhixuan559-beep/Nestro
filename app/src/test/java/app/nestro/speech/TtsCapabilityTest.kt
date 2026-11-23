package app.nestro.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsCapabilityTest {
    @Test fun safeRateIsBounded() {
        assertEquals(0.7f, TtsCapability.safeRate(0f))
        assertEquals(1.3f, TtsCapability.safeRate(2f))
        assertEquals(1.0f, TtsCapability.safeRate(1f))
    }
}
