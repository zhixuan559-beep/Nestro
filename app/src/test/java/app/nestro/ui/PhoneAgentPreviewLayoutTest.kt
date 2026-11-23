package app.nestro.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAgentPreviewLayoutTest {
    @Test
    fun dragOffsetIsClampedToVisibleWindowBounds() {
        assertEquals(
            Offset(-100f, -80f),
            clampPhoneAgentPreviewOffset(Offset(-240f, -120f), maxLeft = 100f, maxTop = 80f)
        )
    }

    @Test
    fun positiveDragCannotMovePreviewOutsideBottomEndAnchor() {
        assertEquals(
            Offset.Zero,
            clampPhoneAgentPreviewOffset(Offset(24f, 36f), maxLeft = 100f, maxTop = 80f)
        )
    }

    @Test
    fun zeroAvailableSpaceKeepsPreviewAtAnchor() {
        assertEquals(
            Offset.Zero,
            clampPhoneAgentPreviewOffset(Offset(-20f, -20f), maxLeft = 0f, maxTop = 0f)
        )
    }
}
