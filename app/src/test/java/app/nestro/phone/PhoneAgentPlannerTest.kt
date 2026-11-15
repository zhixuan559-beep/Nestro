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
    fun generalConversationDoesNotInjectPhoneAgentInstructions() {
        val prompt = "解释一下 Kotlin 协程的结构化并发"
        assertEquals(PhoneAgentPlanner.Category.GENERAL, PhoneAgentPlanner.plan(prompt).category)
        assertTrue(PhoneAgentPlanner.contextFor(prompt).isBlank())
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
}
