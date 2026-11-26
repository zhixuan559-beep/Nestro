package app.nestro.ui

import app.nestro.MainActivity
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        composeRule.onNodeWithText("最近对话", substring = true).performClick()

        composeRule.onNodeWithText("搜索会话标题与内容").assertIsDisplayed()
        composeRule.onNodeWithText("＋ 新建对话").assertIsDisplayed()
    }

    @Test
    fun systemBackReturnsFromToolPageToChat() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("工具与扩展", substring = true).performClick()
        composeRule.onNodeWithText("工具与扩展", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("模型管理").performClick()
        composeRule.onNodeWithText("模型管理").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("有什么可以帮忙？").assertIsDisplayed()
    }

    @Test
    fun systemBackClosesNavigationDrawerWithoutLeavingChat() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("最近对话", substring = true).assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("有什么可以帮忙？").assertIsDisplayed()
        composeRule.onNodeWithText("最近对话", substring = true).assertIsNotDisplayed()
    }

    @Test
    fun toolsDirectoryListsEveryManagementDestination() {
        composeRule.onNodeWithContentDescription("工具与扩展").performClick()

        listOf(
            "模型管理",
            "双模型比较",
            "语音",
            "Skills",
            "工作流",
            "插件",
            "MCP",
            "能力与授权",
            "记忆管理",
            "知识库",
            "产物库",
            "媒体工作区",
            "代码工作区",
            "Git 工作区"
        ).forEach { destination ->
            composeRule.onNodeWithText(destination, substring = false)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun opensProjectSpacesFromNavigationDrawer() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("项目空间", substring = true).performScrollTo().performClick()

        composeRule.onNodeWithText("每个空间拥有独立会话与项目指令。", substring = true).assertIsDisplayed()
    }

    @Test
    fun opensVoiceInputGuidanceFromToolsDirectory() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("工具与扩展", substring = true).performClick()
        composeRule.onNodeWithText("语音", substring = false).performClick()

        composeRule.onNodeWithText("语音输入与输出").assertIsDisplayed()
        composeRule.onNodeWithText(
            "语音输入：在聊天输入框按住麦克风说话，松开后由 Android 系统识别服务转写并发送。",
            substring = true
        ).assertIsDisplayed()
    }


    @Test
    fun opensKnowledgeBaseAndReturnsToNewConversation() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("工具与扩展", substring = true).performClick()
        // “知识库”是工具页第 11 个入口，手机屏下在折叠线以下：直接点按会把触摸注到屏外坐标、被静默丢弃，
        // 页面切不过去（#1645 即因此在 :118 找不到“本地知识库”节点）。先滚到可见再点（同文件 :85/:93 既有模式）。
        composeRule.onNodeWithText("知识库", substring = false).performScrollTo().performClick()

        composeRule.onNodeWithText("本地知识库").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("新建对话", substring = true).performClick()
        composeRule.onNodeWithText("Nestro").assertIsDisplayed()
    }

    @Test
    fun opensWorkflowAndSettingsDestinations() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("工具与扩展", substring = true).performClick()
        composeRule.onNodeWithText("工作流", substring = false).performClick()
        composeRule.onNodeWithText("工作流").assertIsDisplayed()
        composeRule.onNodeWithText("自动调度").performClick()
        composeRule.onNodeWithText("本机保存 · 支持重复日程与后台提醒").assertIsDisplayed()
        composeRule.onNodeWithText("返回工作流").performClick()
        composeRule.onNodeWithText("工作流").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("设置", substring = true).performClick()
        composeRule.onNodeWithText("设置", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("本地数据备份").assertIsDisplayed()
    }

    @Test
    fun modelSettingsCanBeOpenedFromTheTopBar() {
        composeRule.onNodeWithContentDescription("工具与扩展").performClick()
        composeRule.onNodeWithText("模型管理").performClick()
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

        // 无模型提示是追加到聊天列表底部的消息，可能在折叠线以下；先滚到可见再断言。
        composeRule.onNodeWithText(
            "还没有配置模型。进入「模型管理」，填写兼容 OpenAI Chat Completions 的 API 地址、模型名称和 Key。"
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun modelConfigurationFormShowsValidationInsteadOfSavingIncompleteData() {
        composeRule.onNodeWithContentDescription("工具与扩展").performClick()
        composeRule.onNodeWithText("模型管理").performClick()
        composeRule.onNodeWithText("模型管理").assertIsDisplayed()

        composeRule.onNodeWithText("保存模型配置").performScrollTo().performClick()

        composeRule.onNodeWithText("配置名称需为 1 到 40 个字符").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun modelConnectionTestStaysDisabledUntilRequiredFieldsAreProvided() {
        composeRule.onNodeWithContentDescription("工具与扩展").performClick()
        composeRule.onNodeWithText("模型管理").performClick()

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
            composeRule.onNodeWithText("项目空间", substring = true).performScrollTo().performClick()
            composeRule.onNodeWithText("项目空间", substring = false).assertIsDisplayed()
        } finally {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }


    @Test
    fun expertTemplateLibraryShowsBuiltInsAndFiltersBySearch() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("专家与专家团", substring = true).performScrollTo().performClick()
        composeRule.onNodeWithText("模板库").performClick()

        composeRule.onNodeWithText("内置模板 10 个 · 自定义模板 0 个").assertIsDisplayed()
        composeRule.onNodeWithText("深度研究员", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("搜索模板名称、说明或指令").performScrollTo().performTextInput("风险审查员")
        composeRule.onNodeWithText("✦  风险审查员", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("深度研究员", substring = true).assertIsNotDisplayed()
    }

    @Test
    fun phoneAgentIsNotExposedAsPrimaryNavigationDestination() {
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onAllNodesWithText("手机自动化").assertCountEquals(0)
        composeRule.onNodeWithText("工具与扩展", substring = true).performClick()
        composeRule.onAllNodesWithText("手机自动化").assertCountEquals(0)
    }

}
