package app.nestro.ui

import app.nestro.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillLibraryInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun createsDisablesAndReEnablesALocalSkill() {
        composeRule.activity.getSharedPreferences("nestro_local", 0).edit().clear().commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("技能库").performScrollTo().performClick()
        composeRule.onNodeWithText("本地技能库").assertIsDisplayed()

        composeRule.onNodeWithText("＋ 创建技能").performScrollTo().performClick()
        composeRule.onNodeWithText("技能名称").performTextInput("交互测试技能")
        composeRule.onNodeWithText("工作指令").performTextInput("仅用于验证技能创建、停用和启用流程。")
        composeRule.onNodeWithText("保存").performClick()
        composeRule.onNodeWithText("交互测试技能").assertIsDisplayed()

        composeRule.onNodeWithText("停用").performClick()
        composeRule.onNodeWithText("已停用").assertIsDisplayed()
        composeRule.onNodeWithText("启用").performClick()
        composeRule.onNodeWithText("已启用").assertIsDisplayed()
    }
}
