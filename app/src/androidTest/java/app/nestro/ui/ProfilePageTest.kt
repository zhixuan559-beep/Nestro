package app.nestro.ui

import app.nestro.MainActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

/**
 * 改版第 c 步：资料页（设计图 03）——顶栏名字胶囊进，各组齐全、行点得进、自动备份开关是真开关。
 */
class ProfilePageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun profilePageListsAllSectionsAndNavigatesToExperts() {
        composeRule.onNodeWithContentDescription("打开资料页").performClick()
        // 资料页各组都在：模型与用量、专家、技能、应用、电话、电脑、自动化、记忆、文件、自动备份、设置。
        composeRule.onNodeWithText("模型与用量", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("专家", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("技能", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("应用", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("电话", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("电脑（虚拟屏）", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("自动化", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("记忆", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("它生成的文件", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("自动备份", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("设置", substring = false).performScrollTo().assertIsDisplayed()
        // 点「专家」行进专家页。
        composeRule.onNodeWithText("专家", substring = false).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("专家与专家团", substring = false).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("专家与专家团", substring = false).assertIsDisplayed()
    }

    @Test
    fun autoBackupSwitchTogglesAndRestores() {
        composeRule.onNodeWithContentDescription("打开资料页").performClick()
        composeRule.onNodeWithText("自动备份", substring = false).performScrollTo().assertIsDisplayed()
        val switch = composeRule.onNodeWithContentDescription("自动备份开关")
        // 开关默认开（设计图 03）；点一下关、再点一下开回来，结束状态与开始一致。
        switch.assertIsOn()
        switch.performClick()
        switch.assertIsOff()
        switch.performClick()
        switch.assertIsOn()
    }
}
