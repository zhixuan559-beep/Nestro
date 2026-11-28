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
        // 改版第 b 步：抽屉里不再有“工具与扩展”行，工具目录改走底部「连接你的应用」。
        composeRule.onNodeWithText("连接你的应用", substring = true).performScrollTo().performClick()
        // 先等工具目录页真正落位再点 “Skills”：抽屉关闭有 160ms 真实延时（LaunchedEffect delay），
        // 目录页未挂载时点入口会落空；“模型管理”只在目录页出现，可作落位信号。
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("模型管理").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Skills").performScrollTo().performClick()
        // #1749：performScrollTo() 本身要求节点已在语义树里；切页过渡未完成时节点还不存在，
        // 直接滚会抛“could not find any node”（:39）。先等节点出现，再滚、再等可见。
        // #1750：标题 10 秒未出现——切页点击偶发被吞（布局未稳时点中行间空隙），不是标题不存在；
        // 标题未出现且 “Skills” 入口仍在时重点击，最多 3 次；三次都等不到才让后继断言报失败。
        var titleSeen = false
        var attempts = 0
        while (!titleSeen && attempts < 3) {
            attempts++
            titleSeen = runCatching {
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithText("本地技能库").fetchSemanticsNodes().isNotEmpty()
                }
                true
            }.getOrDefault(false)
            if (!titleSeen && composeRule.onAllNodesWithText("Skills").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithText("Skills").performScrollTo().performClick()
            }
        }
        composeRule.onNodeWithText("本地技能库").performScrollTo()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { composeRule.onNodeWithText("本地技能库").assertIsDisplayed() }.isSuccess
        }
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
