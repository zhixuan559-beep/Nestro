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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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

        // 启动恢复是异步的：必须等它真正落定再建会话、再输入。只等空态标题消失并不可靠——
        // 慢模拟器上恢复（读 Room 全量会话＋初始化模型）常超过 15 秒，等满就继续会撞上恢复后到的
        // 快照覆盖：消息发进旧会话的长列表底部、气泡屏外不可见，三次重试也救不回来（#1719–#1724、
        // #1730 均是这副症状：提示在、用户消息不在、滑到底仍为 0）。
        // restore() 成功后会把 room_storage_initialized 置 true，直接等这个标志位才是恢复落定的信号；
        // 恢复失败（Room 不可用）时标志位不出现，等满 90 秒就按旧逻辑继续，不因此硬失败。
        runCatching {
            composeRule.waitUntil(timeoutMillis = 90_000) {
                composeRule.activity.getSharedPreferences("nestro_local", 0)
                    .getBoolean("room_storage_initialized", false)
            }
        }
        // 标志位只证明 restore() 已返回，其后快照落到界面上还要一个主线程轮转；等空闲后再看标题。
        composeRule.waitForIdle()
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
        // 新会话建好后必须是空的才往下走：若此时空态标题不在屏上，说明视图里仍是恢复出的旧会话
        // 长列表，消息发进去会沉底不可见——直接停在这里报出来，别等发送断言再含糊失败。
        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithText("有什么可以帮忙？").assertIsDisplayed()
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
        // 诊断用：把复合断言拆开，末次尝试的三段状态写进失败信息（第 300 轮计划）——
        // 点击前草稿是否还在、点击后占位文案是否重现、用户气泡是否出现，三者各指一个不同的失败分支。
        var lastDraftPresentBeforeClick = false
        var lastPlaceholderShown = false
        var lastPromptShown = false
        repeat(3) {
            if (sendLanded) return@repeat
            runCatching {
                composeRule.onNode(hasSetTextAction()).performTextClearance()
            }
            composeRule.onNode(hasSetTextAction()).performTextInput(userPrompt)
            // 占位文案节点不在树里 = 草稿还在输入框里；若它已出现，说明草稿在点击前就被抹掉，发送按钮是禁用的。
            lastDraftPresentBeforeClick = composeRule.onAllNodesWithText("输入或按住说话…")
                .fetchSemanticsNodes().isEmpty()
            composeRule.onNodeWithContentDescription("发送", useUnmergedTree = true).onParent().performClick()
            sendLanded = runCatching {
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    lastPlaceholderShown = runCatching {
                        composeRule.onNodeWithText("输入或按住说话…").assertIsDisplayed()
                    }.isSuccess
                    lastPromptShown = runCatching {
                        composeRule.onNodeWithText(userPrompt, substring = true).assertIsDisplayed()
                    }.isSuccess
                    lastPlaceholderShown && lastPromptShown
                }
                true
            }.getOrDefault(false)
        }
        // 三次都没发出去就直接失败，别拖到提示等待再超时——定位信息更明确。
        // 续诊（#1723 的三段状态只证明“草稿被消费、气泡未现”，分不清是哪一路）：
        // 无模型提示状态出现 = sendPrompt 无模型分支跑过（消息已追加进 messages）；
        // 用户文本节点数 = 0 表示消息已不在状态里，≥1 表示在树里但不可见（屏外/长列表）；
        // 空态标题出现 = messages 已被清空。
        if (!sendLanded) {
            val statusShown = composeRule.onAllNodesWithText("请先配置模型 API")
                .fetchSemanticsNodes().isNotEmpty()
            val promptNodeCount = composeRule.onAllNodesWithText(userPrompt, substring = true)
                .fetchSemanticsNodes().size
            // 续诊（#1724 的四段状态分不清“在长列表底部屏外”与“根本不在当前列表里”）：
            // 提示文本与用户文本是同一次发送里相邻追加的两条消息——提示在、用户不在，
            // 说明用户消息被单独丢了；两者都不在，再看滑到底后用户文本是否出现。
            val tipNodeCount = composeRule.onAllNodesWithText("还没有配置模型", substring = true)
                .fetchSemanticsNodes().size
            val promptNodeCountUnmerged = composeRule.onAllNodesWithText(
                userPrompt, substring = true, useUnmergedTree = true
            ).fetchSemanticsNodes().size
            runCatching {
                repeat(6) {
                    composeRule.onRoot().performTouchInput { swipeUp() }
                    composeRule.waitForIdle()
                }
            }
            val promptNodeCountAfterScroll = composeRule.onAllNodesWithText(userPrompt, substring = true)
                .fetchSemanticsNodes().size
            val emptyStateShown = composeRule.onAllNodesWithText("有什么可以帮忙？")
                .fetchSemanticsNodes().isNotEmpty()
            throw AssertionError(
                "发送三次均未落地：末次点击前草稿在场=$lastDraftPresentBeforeClick，" +
                    "占位文案重现=$lastPlaceholderShown，用户气泡出现=$lastPromptShown；" +
                    "无模型提示状态=$statusShown，用户文本节点数=$promptNodeCount，空态标题出现=$emptyStateShown；" +
                    "提示文本节点数=$tipNodeCount，未合并树用户文本节点数=$promptNodeCountUnmerged，" +
                    "滑到底后用户文本节点数=$promptNodeCountAfterScroll"
            )
        }

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
