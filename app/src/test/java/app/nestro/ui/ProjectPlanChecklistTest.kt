package app.nestro.ui

import app.nestro.data.ExpertProjectPlan
import app.nestro.data.ExpertWorkPackage
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectPlanChecklistTest {
    private fun samplePlan() = ExpertProjectPlan(
        projectGoal = "做一份市场分析",
        packages = listOf(
            ExpertWorkPackage(
                id = "work-1",
                memberName = "调研员",
                instruction = "搜集竞品价格\n第二行不进标题",
                acceptanceCriteria = "有价格表"
            ),
            ExpertWorkPackage(
                id = "work-2",
                memberName = "文案",
                instruction = "写出报告摘要",
                acceptanceCriteria = "摘要完整"
            ),
            ExpertWorkPackage(
                id = "work-3",
                memberName = "核对员",
                instruction = "核对数据",
                acceptanceCriteria = "无错误"
            )
        ),
        finalAcceptanceCriteria = "报告完整"
    )

    @Test
    fun mapsLiveStatusesAndTitlesRowsByFirstInstructionLine() {
        val items = projectPlanChecklistItems(
            samplePlan(),
            mapOf("work-1" to "success", "work-2" to "running")
        )

        assertEquals(listOf("work-1", "work-2", "work-3"), items.map { it.packageId })
        assertEquals(listOf("调研员", "文案", "核对员"), items.map { it.memberName })
        assertEquals("搜集竞品价格", items[0].title)
        assertEquals("写出报告摘要", items[1].title)
        assertEquals(listOf("success", "running", "waiting"), items.map { it.status })
    }

    @Test
    fun fallsBackToPackageIdWhenInstructionIsBlank() {
        val plan = ExpertProjectPlan(
            projectGoal = "目标",
            packages = listOf(
                ExpertWorkPackage(
                    id = "work-1",
                    memberName = "调研员",
                    instruction = "   ",
                    acceptanceCriteria = "有产出"
                )
            ),
            finalAcceptanceCriteria = "完成"
        )

        val items = projectPlanChecklistItems(plan, emptyMap())

        assertEquals("work-1", items.single().title)
        assertEquals("waiting", items.single().status)
    }
}
