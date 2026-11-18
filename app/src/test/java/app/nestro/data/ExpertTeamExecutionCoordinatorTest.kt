package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentTaskHandlerRegistry
import app.nestro.agent.AgentTaskStatus
import app.nestro.agent.InMemoryAgentTaskStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertTeamExecutionCoordinatorTest {
    @Test
    fun executesMembersAndCompletesSharedAgentTaskLifecycle() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = ExpertTeamExecutionCoordinator(coordinator)
        val updates = mutableListOf<Pair<String, String>>()
        try {
            val result = service.execute(
                teamId = "team-1",
                teamName = "内容团队",
                teamDescription = "分别分析并提供可执行结论",
                task = "设计一篇文章",
                members = listOf(
                    ExpertTeamMemberTask("选题", "分析用户需求与选题"),
                    ExpertTeamMemberTask("文案", "分析结构与表达")
                ),
                taskId = "expert-team-lifecycle-test",
                onMember = { name, state -> updates += name to state },
                invokeMember = { member, _ -> "${member.name}的独立分析结论" }
            )
            assertEquals(1, result.successfulMembers)
            assertEquals(0, result.failedMembers)
            assertTrue(updates.any { it.second == "running" })
            assertEquals(1, updates.count { it.second == "success" })
            withTimeout(2_000) {
                while (states.get("expert-team-lifecycle-test")?.status != AgentTaskStatus.COMPLETED) delay(10)
            }
            assertEquals(AgentTaskStatus.COMPLETED, states.get("expert-team-lifecycle-test")?.status)
        } finally {
            coordinator.close()
        }
    }
}
