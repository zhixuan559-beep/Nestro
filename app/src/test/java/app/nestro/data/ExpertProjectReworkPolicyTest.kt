package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExpertProjectReworkPolicyTest {
    private fun plan(round: Int = 0) = ExpertProjectPlan(
        projectGoal = "完成项目",
        packages = listOf(
            ExpertWorkPackage(
                id = "package-a",
                memberName = "研究专家",
                instruction = "完成研究",
                acceptanceCriteria = "给出来源"
            )
        ),
        finalAcceptanceCriteria = "成果有来源",
        reworkRound = round
    )

    @Test
    fun onePackageReworkRequiresStructuredPackageFeedback() {
        val result = ExpertProjectReworkPolicy.apply(
            plan(),
            reworkRound = 1,
            reviewFeedbackJson = """{"package-a":"补充可核验的一手来源，并标注出处"}"""
        )
        assertEquals(1, result.reworkRound)
        assertEquals(1, result.packages.size)
        assertTrue(result.packages.single().instruction.contains("补充可核验的一手来源"))
        ExpertProjectPlanExecutor.validate(result, setOf("研究专家"))
    }

    @Test
    fun firstRunCannotCarryReworkFeedbackAndRoundsAreBounded() {
        assertRejected {
            ExpertProjectReworkPolicy.apply(
                plan(),
                reworkRound = 0,
                reviewFeedbackJson = """{"package-a":"返工"}"""
            )
        }
        assertRejected {
            ExpertProjectReworkPolicy.apply(plan(), reworkRound = 3, reviewFeedbackJson = "{}")
        }
    }

    @Test
    fun feedbackKeysMustExactlyMatchReworkPackages() {
        assertRejected {
            ExpertProjectReworkPolicy.apply(
                plan(),
                reworkRound = 1,
                reviewFeedbackJson = """{"unknown-package":"返工"}"""
            )
        }
    }

    @Test
    fun taskIdentityIsStableButChangesForReworkOrChangedInstruction() {
        val members = mapOf(
            "研究专家" to ExpertTeamMemberTask("研究专家", "研究角色", "研究任务"),
            "编辑专家" to ExpertTeamMemberTask("编辑专家", "编辑角色", "编辑任务")
        )
        val original = ExpertProjectTaskIdentity.create(
            "assistant", "team", "project", "团队", "说明", plan(), members
        )
        val same = ExpertProjectTaskIdentity.create(
            "assistant", "team", "project", "团队", "说明", plan(), members
        )
        val rework = ExpertProjectTaskIdentity.create(
            "assistant", "team", "project", "团队", "说明",
            plan().copy(reworkRound = 1), members
        )
        val changed = ExpertProjectTaskIdentity.create(
            "assistant", "team", "project", "团队", "说明",
            plan().copy(packages = plan().packages.map { it.copy(instruction = "按验收意见重做") }), members
        )
        assertEquals(original, same)
        assertNotEquals(original, rework)
        assertNotEquals(original, changed)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected validation failure")
        } catch (_: IllegalArgumentException) {
            // Expected.
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }
}
