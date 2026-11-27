package app.nestro.ui

import app.nestro.MainActivity
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
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
        composeRule.waitForIdle()

        // 返回是异步导航，聊天页标题在断言瞬间可能尚未完成显示；等它真正显示出来再断言，避免时序抖动。
        // 不以空态标题「有什么可以帮忙？」为准：套件里先跑的测试可能已留存消息，恢复后空态标题不再出现；
        // 输入框占位文案只在对话页出现，用它判定已回到对话页，不受残留消息影响。
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("输入或按住说话…").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("输入或按住说话…").assertIsDisplayed()
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

        // 启动恢复是异步的：等恢复落定再输入——恢复出旧消息时空态标题消失；
        // 若恢复出的是空会话（标题不消失），等满 15 秒后继续，已有的草稿/消息守卫会兜底。
        runCatching {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("有什么可以帮忙？").fetchSemanticsNodes().isEmpty()
            }
        }

        // 恢复会选中 Room 里残留的旧会话（套件先跑的测试留下的消息都在里面），提示追加在长列表底部、
        // 节点未合成时 waitUntil 里的 performScrollTo 永远找不到它，盲重发也救不回来；
        // 先开一个全新空会话再发送，提示就是列表里唯二的消息之一，不依赖滚动也能直接显示。
        // 注意抽屉里的「新建对话」只回首页不建会话，要去「最近对话」页点「＋ 新建对话」按钮才建空会话。
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("最近对话", substring = true).assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("最近对话", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("＋ 新建对话").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("＋ 新建对话").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("输入或按住说话…").assertIsDisplayed()
            }.isSuccess
        }

        // 发送按钮是输入框的 trailingIcon：BasicTextField 默认 mergeDescendants 会把它的点击语义
        // 并进输入框节点——按合并树找“发送”只会点到输入框本身（聚焦），发不出去；
        // #1716 的三次重试全部静默落空正是这个原因。改走未合并树找图标节点，再点它的父节点
        // （IconButton 的 clickable 宿主），才是真正点发送。
        // 注意：只看“用户消息文本出现”会误判——没发出去时输入框里本身就含这行字；
        // 真正落地的标志是两者同时成立：草稿被消费（占位文案重现）且消息气泡里出现该文本。
        // 输入框按可编辑节点找（hasSetTextAction），不依赖占位文案：重发时若草稿还在，
        // 占位文案节点已不在语义树里，按它找会直接抛错。
        val userPrompt = "第十项异常态回归"
        var sendLanded = false
        repeat(3) {
            if (sendLanded) return@repeat
            runCatching {
                composeRule.onNode(hasSetTextAction()).performTextClearance()
            }
            composeRule.onNode(hasSetTextAction()).performTextInput(userPrompt)
            composeRule.onNodeWithContentDescription("发送", useUnmergedTree = true).onParent().performClick()
            sendLanded = runCatching {
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    val placeholderShown = runCatching {
                        composeRule.onNodeWithText("输入或按住说话…").assertIsDisplayed()
                    }.isSuccess
                    val promptShown = runCatching {
                        composeRule.onNodeWithText(userPrompt, substring = true).assertIsDisplayed()
                    }.isSuccess
                    placeholderShown && promptShown
                }
                true
            }.getOrDefault(false)
        }
        // 三次都没发出去就直接失败，别拖到提示等待再超时——定位信息更明确。
        if (!sendLanded) throw AssertionError("发送三次均未落地：用户消息始终未上屏")

        // 无模型提示是 send 处理里同步追加的 ChatMessage；新会话只有两条消息，无需滚动即可见。
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText(
                    "还没有配置模型。进入「模型管理」，填写兼容 OpenAI Chat Completions 的 API 地址、模型名称和 Key。"
                ).performScrollTo().assertIsDisplayed()
            }.isSuccess
        }
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
            // 抽屉内容经 LaunchedEffect 异步挂载（drawerMounted），横屏重组后挂载可能晚于点击返回；
            // 先等「项目空间」真正出现再滚到可见并点击，避免时序抖动。
            composeRule.waitUntil(timeoutMillis = 30_000) {
                runCatching {
                    composeRule.onNodeWithText("项目空间", substring = true).performScrollTo().assertIsDisplayed()
                }.isSuccess
            }
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

        // 计数行在弹窗可滚动列内，可能位于折叠线以下；先滚到可见再断言。
        composeRule.onNodeWithText("内置模板 10 个 · 自定义模板 0 个").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("深度研究员", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("搜索模板名称、说明或指令").performScrollTo().performTextInput("风险审查员")
        // 搜索过滤是异步的，过滤结果在断言瞬间可能尚未完成显示；等它真正显示出来再断言，避免时序抖动。
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("✦  风险审查员", substring = false).performScrollTo().assertIsDisplayed()
            }.isSuccess
        }
        // 过滤项刚出现又瞬时不可见会被误判；等它稳定显示再断言。
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("✦  风险审查员", substring = false).performScrollTo().assertIsDisplayed()
            }.isSuccess
        }
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
