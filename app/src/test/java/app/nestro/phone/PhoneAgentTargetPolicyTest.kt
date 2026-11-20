package app.nestro.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentTargetPolicyTest {
    @Test
    fun onlyOneVisibleEnabledMatchCanBeClicked() {
        assertTrue(PhoneAgentTargetPolicy.canClick(1, targetVisible = true, targetEnabled = true))
        assertFalse(PhoneAgentTargetPolicy.canClick(0, targetVisible = true, targetEnabled = true))
        assertFalse(PhoneAgentTargetPolicy.canClick(2, targetVisible = true, targetEnabled = true))
        assertFalse(PhoneAgentTargetPolicy.canClick(1, targetVisible = false, targetEnabled = true))
        assertFalse(PhoneAgentTargetPolicy.canClick(1, targetVisible = true, targetEnabled = false))
    }
}
