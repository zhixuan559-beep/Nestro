package app.nestro.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class VoiceInputButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesButtonRoleAndDisabledAccessibilityState() {
        composeRule.setContent {
            VoiceInputButton(enabled = false, onResult = {})
        }

        composeRule.onNode(
            hasContentDescription("按住说话") and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        ).assertIsNotEnabled()
    }

    @Test
    fun exposesEnabledAccessibilityStateWhenVoiceInputIsAvailable() {
        composeRule.setContent {
            VoiceInputButton(enabled = true, onResult = {})
        }

        composeRule.onNode(
            hasContentDescription("按住说话") and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        ).assertIsEnabled()
    }
}
