package app.nestro.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentPromptBudgetTest {
    @Test fun removesDuplicateScreenLinesAndRespectsLimits() {
        val input = listOf("标题", "重复内容", "重复内容", "细节".repeat(1000)).joinToString("\n")
        val result = PhoneAgentPromptBudget.build("GENERAL", "查看页面", input, "")
        assertTrue(result.screenText.length <= 3_200)
        assertEquals(1, result.screenText.split("重复内容").size - 1)
    }

    @Test fun allocatesMoreOutputForTransactionalPlanningButNeverOldMaximum() {
        val shopping = PhoneAgentPromptBudget.build("SHOPPING", "购买商品", "页面", "历史")
        val general = PhoneAgentPromptBudget.build("GENERAL", "问候", "页面", "历史")
        assertEquals(2_048, shopping.maxOutputTokens)
        assertEquals(1_024, general.maxOutputTokens)
        assertTrue(shopping.maxOutputTokens < 4_096)
    }

    @Test fun boundsActionHistoryIndependently() {
        val result = PhoneAgentPromptBudget.build("BOOKING", "预约", "页面", "动作".repeat(900))
        assertTrue(result.recentActions.length <= 1_000)
        assertTrue(result.screenText.length <= 4_400)
    }
}
