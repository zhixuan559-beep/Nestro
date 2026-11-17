package app.nestro.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test
    fun keepsPrimaryNavigationUsableAcrossPhoneFoldableAndTabletWindowWidths() {
        val windowSize = mutableStateOf(DpSize(320.dp, 720.dp))
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(windowSize.value)
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
    @Test
    fun keepsEveryPrimaryDestinationReachableAcrossPhoneFoldableAndTabletWidths() {
        val windowSize = mutableStateOf(DpSize(320.dp, 720.dp))
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(windowSize.value)
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
        val destinations = listOf(
            "对话历史" to "对话历史",
            "全局记忆" to "全局记忆",
            "本地知识库" to "本地知识库",
            "工作流" to "工作流",
            "项目空间" to "项目空间",
            "手机自动化" to "手机自动化",
            "语音输入" to "语音输入",
            "产物库" to "产物库",
            "能力目录" to "能力目录",
            "我的专家" to "我的专家",
            "专家团队" to "专家团队",
            "能力中心" to "本地技能库",
            "模型管理" to "模型管理",
            "数据与设置" to "数据与设置"
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
                composeRule.onNodeWithText(pageTitle, substring = true).assertIsDisplayed()
            }
        }
    }

}
