package app.nestro.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class NestroWindowSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keepsPrimaryNavigationUsableAcrossPhoneFoldableAndTabletWindowWidths() {
        val windowSize = mutableStateOf(DpSize(320.dp, 720.dp))
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(windowSize.value)
            ) {
                NestroApp()
            }
        }

        val windowSizes = listOf(
            DpSize(320.dp, 720.dp),
            DpSize(393.dp, 852.dp),
            DpSize(673.dp, 900.dp),
            DpSize(840.dp, 900.dp),
            DpSize(1200.dp, 900.dp)
        )

        windowSizes.forEach { size ->
            composeRule.runOnIdle { windowSize.value = size }
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("菜单").performClick()
            composeRule.onNodeWithText("项目空间", substring = true).performClick()
            composeRule.onNodeWithText("项目空间").assertIsDisplayed()
        }
    }
}
