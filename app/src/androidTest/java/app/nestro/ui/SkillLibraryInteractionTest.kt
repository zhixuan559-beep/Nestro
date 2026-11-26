package app.nestro.ui

import app.nestro.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
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
        // 抽屉菜单已无“技能库”项：经“工具与扩展”→“Skills”进入技能库页。
        // 抽屉项文本带图标前缀（"⌘   工具与扩展"），精确匹配不到，必须用 substring。
        composeRule.onNodeWithText("工具与扩展", substring = true).performScrollTo().performClick()
        composeRule.onNodeWithText("Skills").performScrollTo().performClick()
        composeRule.onNodeWithText("本地技能库").assertIsDisplayed()

        composeRule.onNodeWithText("＋ 创建技能").performScrollTo().performClick()
        composeRule.onNodeWithText("技能名称").performTextInput("交互测试技能")
        composeRule.onNodeWithText("工作指令").performTextInput("仅用于验证技能创建、停用和启用流程。")
        composeRule.onNodeWithText("保存").performClick()
        composeRule.onNodeWithText("交互测试技能").performScrollTo().assertIsDisplayed()

        // 技能库页每个已启用技能各带一个“停用”按钮（内置技能＋新建技能共 4 个），不能直接按文本点。
        // 新建技能是唯一带“删除”按钮的卡片（内置技能无删除，NestroApp.kt 技能卡内同一 Row），
        // 用同级“删除”锚定新建技能卡内的“停用”按钮；先滚到可见再点（新建卡片在列表末尾）。
        composeRule.onNode(hasText("停用").and(hasAnySibling(hasText("删除")))).performScrollTo().performClick()
        composeRule.onNodeWithText("已停用").assertIsDisplayed()
        // 重新启用后 4 张技能卡（3 个内置＋新建）都显示“已启用”，不能按文本断言唯一节点；
        // 计 4 个即证明新建技能已回到启用态（若启用失败会只剩 3 个，且仍有“已停用”）。
        composeRule.onNodeWithText("启用").performClick()
        composeRule.onAllNodesWithText("已启用").assertCountEquals(4)
    }
}
