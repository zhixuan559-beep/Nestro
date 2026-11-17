package app.nestro.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class CapabilityDirectoryWindowSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun capabilityDirectoryRemainsScrollableOnPhoneAndTabletWindows() {
        val windowSize = mutableStateOf(DpSize(320.dp, 640.dp))
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(windowSize.value)
            ) {
                CapabilityDirectoryPage()
            }
        }

        listOf(
            DpSize(320.dp, 640.dp),
            DpSize(393.dp, 852.dp),
            DpSize(673.dp, 900.dp),
            DpSize(840.dp, 900.dp),
            DpSize(1200.dp, 900.dp)
        ).forEach { size ->
            composeRule.runOnIdle { windowSize.value = size }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("能力目录").assertIsDisplayed()
            composeRule.onNodeWithText("社区目录 HTTPS 地址").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("语音输出（预留）").performScrollTo().assertIsDisplayed()
        }
    }
}
