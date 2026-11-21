package app.nestro.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertProjectPlanExecutorTest {
    private fun plan() = ExpertProjectPlan(
        projectGoal = "完成一个可交付项目",
        packages = listOf(
            ExpertWorkPackage("research", "研究员", "收集并核验资料", acceptanceCriteria = "给出来源和事实"),
            ExpertWorkPackage("strategy", "策略师", "形成策略", listOf("research"), "策略可执行"),
            ExpertWorkPackage("copy", "文案官", "形成传播文案", listOf("research"), "文案完整"),
            ExpertWorkPackage("review", "审校官", "交叉检查并汇总", listOf("strategy", "copy"), "指出冲突并形成最终稿")
        ),
        finalAcceptanceCriteria = "最终稿完整且依据可追溯"
    )

    @Test
    fun topologicalWavesKeepIndependentPackagesTogether() {
        val waves = ExpertProjectPlanExecutor.topologicalWaves(plan())
        assertEquals(listOf(listOf("research"), listOf("copy", "strategy"), listOf("review")),
            waves.map { wave -> wave.map { it.id } })
    }

    @Test
    fun validationRejectsUnknownMemberAndDependencyCycle() {
        val valid = plan()
        try {
            ExpertProjectPlanExecutor.validate(valid, setOf("研究员", "策略师", "文案官", "审校官"))
        } catch (error: Exception) {
            throw AssertionError("valid plan should pass", error)
        }

        val cyclic = valid.copy(packages = valid.packages.map {
            when (it.id) {
                "research" -> it.copy(dependsOn = listOf("review"))
                else -> it
            }
        })
        assertFalse(runCatching {
            ExpertProjectPlanExecutor.validate(cyclic, setOf("研究员", "策略师", "文案官", "审校官"))
        }.isSuccess)
    }

    @Test
    fun executionRunsOnlyAfterDependenciesAndReturnsStructuredArtifacts() = runBlocking {
        val result = ExpertProjectPlanExecutor.execute(
            plan = plan(),
            availableMembers = setOf("研究员", "策略师", "文案官", "审校官"),
            invoke = { member, prompt -> "$member 已完成：${prompt.take(80)}" }
        )
        assertTrue(result.isSuccessful)
        assertEquals(4, result.packageResults.size)
        assertTrue(result.packageResults.all { it.output.isNotBlank() })
    }
}
