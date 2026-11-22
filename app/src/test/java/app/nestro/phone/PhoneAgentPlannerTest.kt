package app.nestro.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentPlannerTest {
    @Test
    fun shoppingGoalIncludesTotalCostAndHumanConfirmation() {
        val plan = PhoneAgentPlanner.plan("帮我买一杯咖啡，比较价格和配送费")
        assertEquals(PhoneAgentPlanner.Category.SHOPPING, plan.category)
        assertTrue(plan.steps.any { it.kind == PhoneAgentPlanner.StepKind.COMPARE_TOTAL_COST })
        assertTrue(plan.steps.any {
            it.kind == PhoneAgentPlanner.StepKind.REQUEST_USER_CONFIRMATION &&
                it.risk == PhoneAgentPlanner.Risk.HIGH &&
                it.requiresUserConfirmation
        })
    }

    @Test
    fun bookingGoalIncludesAvailabilityVerification() {
        val plan = PhoneAgentPlanner.plan("帮我预约明天下午的餐厅")
        assertEquals(PhoneAgentPlanner.Category.BOOKING, plan.category)
        assertTrue(plan.steps.any { it.kind == PhoneAgentPlanner.StepKind.VERIFY_PAGE })
        assertTrue(plan.steps.any { it.requiresUserConfirmation })
    }

    @Test
    fun foodDeliveryGoalRemainsFoodOrder() {
        val plan = PhoneAgentPlanner.plan("帮我点一份外卖")
        assertEquals(PhoneAgentPlanner.Category.FOOD_ORDER, plan.category)
    }

    @Test
    fun generalConversationDoesNotInjectPhoneAgentInstructions() {
        val prompt = "解释一下 Kotlin 协程的结构化并发"
        assertEquals(PhoneAgentPlanner.Category.GENERAL, PhoneAgentPlanner.plan(prompt).category)
        assertTrue(PhoneAgentPlanner.contextFor(prompt).isBlank())
    }

    @Test
    fun ordinaryChatNeverCreatesPhoneAgentTaskEvenWhenPromptContainsActionKeywords() {
        val plan = PhoneAgentPlanner.plan("帮我搜索咖啡价格")
        assertEquals(PhoneAgentPlanner.Category.SHOPPING, plan.category)
        assertTrue(!PhoneAgentPlanner.shouldCreateTask(taskModeEnabled = false, plan = plan))
    }

    @Test
    fun modelSelectedPhonePathCanExecuteGeneralGoalsOnlyInExplicitTaskMode() {
        val actionPlan = PhoneAgentPlanner.plan("帮我预约明天的餐厅")
        val generalPlan = PhoneAgentPlanner.plan("打开浏览器并查找指定页面")
        assertTrue(PhoneAgentPlanner.shouldCreateTask(taskModeEnabled = true, plan = actionPlan))
        assertTrue(PhoneAgentPlanner.shouldCreateTask(taskModeEnabled = true, plan = generalPlan))
        assertTrue(!PhoneAgentPlanner.shouldCreateTask(taskModeEnabled = false, plan = generalPlan))
    }

    @Test
    fun creativeAssetTaskGetsDownloadAndProjectImportSteps() {
        val plan = PhoneAgentPlanner.plan("帮我下载几张城市夜景图片素材用于视频剪辑")
        assertEquals(PhoneAgentPlanner.Category.CREATIVE_ASSET, plan.category)
        assertTrue(plan.steps.any { it.kind == PhoneAgentPlanner.StepKind.DOWNLOAD_ASSET })
        assertTrue(plan.steps.any { it.kind == PhoneAgentPlanner.StepKind.IMPORT_ASSET })
        assertTrue(PhoneAgentPlanner.shouldCreateTask(taskModeEnabled = true, plan = plan))
    }

    @Test
    fun formTaskHandsSensitiveSubmissionBackToUser() {
        val plan = PhoneAgentPlanner.plan("帮我填写并提交报名表")
        assertEquals(PhoneAgentPlanner.Category.FORM, plan.category)
        assertTrue(plan.steps.any {
            it.kind == PhoneAgentPlanner.StepKind.HANDOFF_TO_USER &&
                it.requiresUserConfirmation
        })
    }
    @Test
    fun replanningKeepsOnlyRemainingStepsAndMarksFirstForScreenVerification() {
        val plan = PhoneAgentPlanner.plan("帮我比较并预订一个餐厅")
        val replanned = PhoneAgentPlanner.replanFromCurrentState(plan, "当前页面显示餐厅列表", 1)
        assertEquals(plan.steps.drop(1).size, replanned.steps.size)
        if (replanned.steps.isNotEmpty()) assertTrue(replanned.steps.first().title.contains("重新核验"))
    }

    @Test
    fun capabilityLoadingIsOnDemandAndCostBounded() {
        val selected = PhoneAgentAgent.selectCapabilities(
            "打开浏览器",
            listOf(
                PhoneAgentAgent.AgentCapability("浏览器", estimatedCost = 2),
                PhoneAgentAgent.AgentCapability("支付", estimatedCost = 5),
                PhoneAgentAgent.AgentCapability("系统", PhoneAgentAgent.LoadPolicy.EAGER, 2)
            ),
            maxCost = 4
        )
        assertTrue(selected.any { it.id == "浏览器" })
        assertTrue(selected.any { it.id == "系统" })
        assertTrue(selected.none { it.id == "支付" })
    }

}
