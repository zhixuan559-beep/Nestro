package app.nestro.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class NestroWindowSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val windowSizes = listOf(
        DpSize(320.dp, 720.dp),
        DpSize(393.dp, 852.dp),
        DpSize(673.dp, 900.dp),
        DpSize(840.dp, 900.dp),
        DpSize(1200.dp, 900.dp)
    )

    @Test(timeout = 300_000)
    fun keepsPrimaryNavigationUsableAcrossPhoneFoldableAndTabletWindowWidths() {
        val windowSize = mutableStateOf(windowSizes.first())
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(windowSize.value)) {
                NestroApp()
            }
        }

        val destinations = listOf(
            "新建对话" to "有什么可以帮忙？",
            "最近对话" to "对话历史",
            "项目空间" to "项目空间",
            "专家与专家团" to "专家与专家团",
            "工具与扩展" to "工具与扩展",
            "设置" to "设置"
        )

        windowSizes.forEach { size ->
            composeRule.runOnIdle { windowSize.value = size }
            composeRule.waitForIdle()
            destinations.forEach { (navigationLabel, pageTitle) ->
                composeRule.onNodeWithContentDescription("菜单").performClick()
                composeRule.onNodeWithText(navigationLabel, substring = true)
                    .performScrollTo()
                    .performClick()
                composeRule.waitForIdle()
                println("NestroWindowSizeTest checking size=$size destination=$navigationLabel title=$pageTitle")
                // 页面切换是异步的，标题在断言瞬间可能尚未完成显示；等它真正显示出来再断言，避免时序抖动。
                composeRule.waitUntil(timeoutMillis = 30_000) {
                    runCatching {
                        composeRule.onNodeWithText(pageTitle, substring = false).assertIsDisplayed()
                    }.isSuccess
                }
                composeRule.onNodeWithText(pageTitle, substring = false).assertIsDisplayed()
            }
        }
    }

    @Test(timeout = 300_000)
    fun keepsPhoneAgentAndCalendarOutOfPrimaryNavigationAcrossWindowWidths() {
        val windowSize = mutableStateOf(windowSizes.first())
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(windowSize.value)) {
                NestroApp()
            }
        }

        windowSizes.forEach { size ->
            composeRule.runOnIdle { windowSize.value = size }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("菜单").performClick()
            composeRule.onAllNodesWithText("手机自动化").assertCountEquals(0)
            composeRule.onAllNodesWithText("日历").assertCountEquals(0)
            composeRule.onNodeWithText("工具与扩展", substring = true).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("关闭菜单").performClick()
        }
    }
}
