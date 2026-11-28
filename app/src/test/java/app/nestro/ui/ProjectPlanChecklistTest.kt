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

    @Test
    fun statusLineShowsOnlyOneLineWhileWorking() {
        // 改版第 d 步（设计图 05）：干活时对话里默认只显示状态行，清单默认收起。
        assertEquals(
            "正在处理 · 专家团干活中",
            planStatusLineText(true, samplePlan(), mapOf("work-1" to "success"), "")
        )
        assertEquals(
            "正在处理 · 正在订酒店",
            planStatusLineText(true, samplePlan(), mapOf("work-1" to "success"), "正在订酒店")
        )
    }

    @Test
    fun statusLineWithoutPlanFallsBackToThinking() {
        assertEquals("正在思考…", planStatusLineText(true, null, emptyMap(), ""))
    }

    @Test
    fun statusLineShowsDoneCountAfterWorkFinishes() {
        assertEquals(
            "已完成 · 3/3 项",
            planStatusLineText(
                false, samplePlan(),
                mapOf("work-1" to "success", "work-2" to "success", "work-3" to "success"), ""
            )
        )
        assertEquals(
            "计划清单 · 已完成 1/3 项",
            planStatusLineText(false, samplePlan(), mapOf("work-1" to "success"), "")
        )
    }

    @Test
    fun statusLineIsNullWhenIdleAndNoPlan() {
        assertEquals(null, planStatusLineText(false, null, emptyMap(), ""))
    }
}
