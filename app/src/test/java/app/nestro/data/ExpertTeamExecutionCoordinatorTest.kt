package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolPermission
import app.nestro.agent.AgentToolSpec
import app.nestro.agent.InMemoryAgentToolCatalog
import app.nestro.agent.AgentTaskHandlerRegistry
import app.nestro.agent.AgentTaskStatus
import app.nestro.agent.InMemoryAgentTaskStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
    @Test
    fun memberReceivesOnlyItsDeclaredToolsAndLeaseClosesAfterInvocation() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = ExpertTeamExecutionCoordinator(coordinator)
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("knowledge.search", "搜索知识库", AgentToolPermission.READ_ONLY)) {
                app.nestro.agent.AgentToolResult(true, "结果")
            }
            register(AgentToolSpec("device.click", "点击设备", AgentToolPermission.DEVICE_ACTION)) {
                app.nestro.agent.AgentToolResult(true, "已点击")
            }
        }
        var captured: app.nestro.agent.AgentToolDiscovery? = null
        AgentCapabilityRegistry.register("expert_team", "选题", setOf("knowledge.search"))
        AgentCapabilityRegistry.register("expert_team", "文案", setOf("knowledge.search"))
        try {
            val result = service.execute(
                teamId = "team-tools",
                teamName = "内容团队",
                teamDescription = "分析任务",
                task = "设计一篇文章",
                members = listOf(
                    ExpertTeamMemberTask("选题", "分析用户需求与选题"),
                    ExpertTeamMemberTask("文案", "分析结构与表达")
                ),
                taskId = "expert-team-tool-scope",
                invokeMember = { _, _ -> error("应使用工具感知调用") },
                toolCatalog = catalog,
                invokeMemberWithTools = { _, _, access ->
                    captured = access
                    assertEquals(listOf("knowledge.search"), access?.list()?.map { it.id })
                    "已完成分析"
                }
            )
            assertTrue(result.isUsable)
            assertEquals(null, captured?.find("knowledge.search"))
            assertEquals(null, captured?.find("device.click"))
        } finally {
            AgentCapabilityRegistry.unregister("expert_team", "选题")
            AgentCapabilityRegistry.unregister("expert_team", "文案")
            coordinator.close()
        }
    }

    @Test
    fun parallelTeamResumesOnlyMembersMissingFromDurableCheckpoint() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = ExpertTeamExecutionCoordinator(tasks)
        val members = listOf(
            ExpertTeamMemberTask("研究员", "负责研究", "核验资料"),
            ExpertTeamMemberTask("策略师", "负责策略", "形成策略")
        )
        val secondMemberStarted = CompletableDeferred<Unit>()
        val firstRun = launch {
            runCatching {
                service.execute(
                    teamId = "team-resume",
                    teamName = "恢复团队",
                    teamDescription = "独立并行分工",
                    task = "完成项目研究",
                    members = members,
                    strategy = ExpertTeamSchedulingStrategy.PARALLEL_WORK_PACKAGES,
                    taskId = "expert-team-resume-task",
                    invokeMember = { member, _ ->
                        if (member.name == "研究员") "研究员已完成"
                        else {
                            secondMemberStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                )
            }
        }
        withTimeout(2_000) { secondMemberStarted.await() }
        withTimeout(2_000) {
            while (states.get("expert-team-resume-task")?.checkpoint?.contains("研究员已完成") != true) {
                delay(10)
            }
        }
        tasks.pause("expert-team-resume-task")
        firstRun.join()
        assertEquals(AgentTaskStatus.PAUSED, tasks.state("expert-team-resume-task")?.status)

        val resumedCalls = mutableListOf<String>()
        val result = service.execute(
            teamId = "team-resume",
            teamName = "恢复团队",
            teamDescription = "独立并行分工",
            task = "完成项目研究",
            members = members,
            strategy = ExpertTeamSchedulingStrategy.PARALLEL_WORK_PACKAGES,
            taskId = "expert-team-resume-task",
            invokeMember = { member, _ ->
                resumedCalls += member.name
                "${member.name}已完成"
            }
        )
        assertEquals(listOf("策略师"), resumedCalls)
        assertEquals(2, result.successfulMembers)
        assertEquals(AgentTaskStatus.COMPLETED, tasks.state("expert-team-resume-task")?.status)
        tasks.close()
    }

}
