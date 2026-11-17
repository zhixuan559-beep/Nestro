package app.nestro.ui

import app.nestro.MainActivity
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class NestroAppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainScreenRendersApplicationHeader() {
        composeRule.onNodeWithText("Nestro").assertIsDisplayed()
    }
    @Test
    fun opensConversationHistoryFromAccessibleNavigationDrawer() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("对话历史", substring = true).performClick()

        composeRule.onNodeWithText("搜索会话标题与内容").assertIsDisplayed()
        composeRule.onNodeWithText("＋ 新建对话").assertIsDisplayed()
    }

    @Test
    fun opensProjectSpacesFromNavigationDrawer() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("项目空间", substring = true).performClick()

        composeRule.onNodeWithText("每个空间拥有独立会话与项目指令。", substring = true).assertIsDisplayed()
    }

    @Test
    fun opensVoiceInputGuidanceFromNavigationDrawer() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("语音输入", substring = true).performClick()

        composeRule.onNodeWithText("语音输入").assertIsDisplayed()
        composeRule.onNodeWithText("当前不内置语音合成或第三方语音插件。", substring = true).assertIsDisplayed()
    }


    @Test
    fun opensKnowledgeBaseAndReturnsToWorkspace() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("本地知识库", substring = true).performClick()

        composeRule.onNodeWithText("本地知识库").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("工作台", substring = true).performClick()
        composeRule.onNodeWithText("Nestro").assertIsDisplayed()
    }

    @Test
    fun opensWorkflowAndDataSettingsDestinations() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("工作流", substring = true).performClick()
        composeRule.onNodeWithText("工作流").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("数据与设置", substring = true).performClick()
        composeRule.onNodeWithText("数据与设置").assertIsDisplayed()
        composeRule.onNodeWithText("本地数据备份").assertIsDisplayed()
    }

    @Test
    fun modelSettingsCanBeOpenedFromTheTopBar() {
        composeRule.onNodeWithContentDescription("模型设置").performClick()
        composeRule.onNodeWithText("模型管理").assertIsDisplayed()
    }


    @Test
    fun sendingWithoutAModelShowsActionableConfigurationGuidance() {
        composeRule.activity.getSharedPreferences("nestro_local", 0)
            .edit()
            .clear()
            .commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("输入或按住说话…").performTextInput("第十项异常态回归")
        composeRule.onNodeWithContentDescription("发送").performClick()

        composeRule.onNodeWithText(
            "还没有配置模型。进入「模型管理」，填写兼容 OpenAI Chat Completions 的 API 地址、模型名称和 Key。"
        ).assertIsDisplayed()
    }

    @Test
    fun modelConfigurationFormShowsValidationInsteadOfSavingIncompleteData() {
        composeRule.onNodeWithContentDescription("模型设置").performClick()
        composeRule.onNodeWithText("模型管理").assertIsDisplayed()

        composeRule.onNodeWithText("保存模型配置").performScrollTo().performClick()

        composeRule.onNodeWithText("配置名称需为 1 到 40 个字符").assertIsDisplayed()
    }

    @Test
    fun modelConnectionTestStaysDisabledUntilRequiredFieldsAreProvided() {
        composeRule.onNodeWithContentDescription("模型设置").performClick()

        composeRule.onNodeWithText("测试当前编辑配置")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun remainsNavigableAfterLandscapeWindowChange() {
        try {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("菜单").performClick()
            composeRule.onNodeWithText("项目空间", substring = true).performClick()
            composeRule.onNodeWithText("项目空间").assertIsDisplayed()
        } finally {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

}
