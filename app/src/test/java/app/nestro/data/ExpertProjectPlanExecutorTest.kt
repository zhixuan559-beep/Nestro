package app.nestro.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    @Test
    fun executionRestoresSuccessfulPackagesAndRunsOnlyRemainingDagNodes() = runBlocking {
        val calls = mutableListOf<String>()
        val checkpointSizes = mutableListOf<Int>()
        val result = ExpertProjectPlanExecutor.execute(
            plan = plan(),
            availableMembers = setOf("研究员", "策略师", "文案官", "审校官"),
            resumeResults = listOf(
                ExpertWorkPackageResult("research", "研究员", "success", output = "已保存的研究成果")
            ),
            invoke = { member, prompt ->
                calls += member
                "$member 已完成：${prompt.take(80)}"
            },
            onPackageResult = { rows -> checkpointSizes += rows.size }
        )
        assertTrue(result.isSuccessful)
        assertEquals(3, calls.size)
        assertFalse("研究员" in calls)
        assertEquals(listOf(3, 4), checkpointSizes)
    }

    @Test
    fun successfulPackageIsCheckpointedBeforeParallelSiblingFinishes() = runBlocking {
        val plan = ExpertProjectPlan(
            projectGoal = "完成并行项目",
            packages = listOf(
                ExpertWorkPackage("research", "研究员", "完成研究", acceptanceCriteria = "研究结果"),
                ExpertWorkPackage("strategy", "策略师", "形成策略", acceptanceCriteria = "策略结果")
            ),
            finalAcceptanceCriteria = "两项成果齐全"
        )
        val checkpointReady = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<List<ExpertWorkPackageResult>>()
        val execution = launch {
            runCatching {
                ExpertProjectPlanExecutor.execute(
                    plan = plan,
                    availableMembers = setOf("研究员", "策略师"),
                    invoke = { member, _ ->
                        if (member == "研究员") "研究成果已完成" else awaitCancellation()
                    },
                    onPackageResult = { rows ->
                        snapshots += rows
                        if (rows.any { it.packageId == "research" && it.status == "success" }) {
                            checkpointReady.complete(Unit)
                        }
                    }
                )
            }
        }
        withTimeout(2_000) { checkpointReady.await() }
        execution.cancel()
        execution.join()
        assertTrue(snapshots.last().any {
            it.packageId == "research" && it.status == "success" && it.output == "研究成果已完成"
        })
    }

    @Test
    fun checksPauseGateBeforeAndAfterEachDependencyWave() = runBlocking {
        var pauseChecks = 0
        val result = ExpertProjectPlanExecutor.execute(
            plan = plan(),
            availableMembers = setOf("研究员", "策略师", "文案官", "审校官"),
            awaitIfPaused = { pauseChecks++ },
            invoke = { member, _ -> "$member 已完成" }
        )

        assertTrue(result.isSuccessful)
        assertEquals(6, pauseChecks)
    }

    @Test
    fun resumeRejectsDuplicateOrMismatchedMemberCheckpointRows() = runBlocking {
        val valid = ExpertWorkPackageResult("research", "研究员", "success", output = "saved")
        val duplicate = runCatching {
            ExpertProjectPlanExecutor.execute(
                plan(), setOf("研究员", "策略师", "文案官", "审校官"),
                resumeResults = listOf(valid, valid),
                invoke = { _, _ -> "should not run" }
            )
        }
        assertTrue(duplicate.isFailure)

        val mismatchedMember = runCatching {
            ExpertProjectPlanExecutor.execute(
                plan(), setOf("研究员", "策略师", "文案官", "审校官"),
                resumeResults = listOf(valid.copy(memberName = "冒名成员")),
                invoke = { _, _ -> "should not run" }
            )
        }
        assertTrue(mismatchedMember.isFailure)
    }

}
