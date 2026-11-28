package app.nestro.ui

import app.nestro.MainActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * 改版第 e 步：通话页（设计图 09/10/11）——顶栏电话键进入，三键齐全，视频去框、小窗可互换。
 */
class CallPageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun openCallPage() {
        composeRule.onNodeWithContentDescription("电话").performClick()
        composeRule.onNodeWithText("挂断", substring = false).assertIsDisplayed()
    }

    @Test
    fun callPageOpensFromTopBarAndHangupReturnsToChat() {
        openCallPage()
        composeRule.onNodeWithText("小轩", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("音频 · 免提中", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("视频", substring = false).assertIsDisplayed()
        // 测试环境未配置语音服务：通话页如实提示并给出配置入口，不编造已接通。
        composeRule.onNodeWithText("去配置语音服务", substring = false).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("挂断").performClick()
        composeRule.onNodeWithContentDescription("电话").assertIsDisplayed()
    }

    @Test
    fun audioButtonTogglesMuteLabel() {
        openCallPage()
        composeRule.onNodeWithContentDescription("音频").performClick()
        composeRule.onNodeWithText("音频 · 已静音", substring = false).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("音频").performClick()
        composeRule.onNodeWithText("音频 · 免提中", substring = false).assertIsDisplayed()
    }

    @Test
    fun videoModeShowsSelfViewAndSwapsOnTap() {
        openCallPage()
        composeRule.onNodeWithContentDescription("视频").performClick()
        composeRule.onNodeWithText("关视频", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("你的画面", substring = false).assertIsDisplayed()
        // 点小窗互换：大窗变成你的画面，小窗变成吉祥物并带「点我互换」。
        composeRule.onNodeWithContentDescription("切换大小").performClick()
        composeRule.onNodeWithText("你的画面（已放大）", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("点我互换", substring = false).assertIsDisplayed()
        // 再点小窗换回来。
        composeRule.onNodeWithContentDescription("切换大小").performClick()
        composeRule.onNodeWithText("你的画面", substring = false).assertIsDisplayed()
        // 关视频回语音模式。
        composeRule.onNodeWithContentDescription("视频").performClick()
        composeRule.onNodeWithText("视频", substring = false).assertIsDisplayed()
    }
}
