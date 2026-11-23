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
import java.util.concurrent.atomic.AtomicBoolean

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
            register(AgentToolSpec("sensitive.read", "需要确认的只读工具", AgentToolPermission.READ_ONLY, requiresConfirmation = true)) {
                app.nestro.agent.AgentToolResult(true, "需确认")
            }
        }
        var captured: app.nestro.agent.AgentToolDiscovery? = null
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

    @Test
    fun projectPlanCheckpointRetainsFingerprintAndResumesAfterCompletedWave() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = ExpertTeamExecutionCoordinator(tasks)
        val plan = ExpertProjectPlan(
            projectGoal = "完成项目研究",
            packages = listOf(
                ExpertWorkPackage("research", "研究员", "完成研究", acceptanceCriteria = "研究成果"),
                ExpertWorkPackage("strategy", "策略师", "形成策略", listOf("research"), "策略成果"),
                ExpertWorkPackage("copy", "文案官", "形成文案", listOf("research"), "文案成果")
            ),
            finalAcceptanceCriteria = "结果完整"
        )
        val members = mapOf(
            "研究员" to ExpertTeamMemberTask("研究员", "负责研究"),
            "策略师" to ExpertTeamMemberTask("策略师", "负责策略"),
            "文案官" to ExpertTeamMemberTask("文案官", "负责文案")
        )
        val parallelWaveStarted = CompletableDeferred<Unit>()
        val firstRun = launch {
            runCatching {
                service.executeProjectPlan(
                    teamId = "project-plan-resume",
                    teamName = "项目团队",
                    teamDescription = "分工执行",
                    plan = plan,
                    members = members,
                    projectId = "project-1",
                    taskId = "project-plan-resume-task",
                    invokeMember = { member, _ ->
                        if (member.name == "研究员") "研究成果已保存"
                        else {
                            parallelWaveStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                )
            }
        }
        withTimeout(2_000) { parallelWaveStarted.await() }
        val checkpoint = states.get("project-plan-resume-task")?.checkpoint.orEmpty()
        assertTrue(checkpoint.contains("\"planFingerprint\""))
        assertTrue(checkpoint.contains("研究成果已保存"))
        tasks.pause("project-plan-resume-task")
        firstRun.join()
        assertEquals(AgentTaskStatus.PAUSED, tasks.state("project-plan-resume-task")?.status)

        val resumedCalls = mutableListOf<String>()
        val resumed = service.executeProjectPlan(
            teamId = "project-plan-resume",
            teamName = "项目团队",
            teamDescription = "分工执行",
            plan = plan,
            members = members,
            projectId = "project-1",
            taskId = "project-plan-resume-task",
            invokeMember = { member, _ ->
                resumedCalls += member.name
                "${member.name} 已完成"
            }
        )
        assertTrue(resumed.isSuccessful)
        assertEquals(2, resumedCalls.size)
        assertFalse("研究员" in resumedCalls)
        tasks.close()
    }

    @Test
    fun leadHandoffResumesAtTheDelegatedExpertWithoutRepeatingTheLead() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = ExpertTeamExecutionCoordinator(tasks)
        val members = listOf(
            ExpertTeamMemberTask("文案官", "负责文案和公众号内容表达"),
            ExpertTeamMemberTask("研究员", "负责资料研究与事实核验")
        )
        val secondMemberStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstRun = kotlinx.coroutines.launch {
            runCatching {
                service.execute(
                    teamId = "lead-handoff-resume",
                    teamName = "主导交接团队",
                    teamDescription = "先产出，再由研究员核验",
                    task = "写一篇公众号文章",
                    members = members,
                    strategy = ExpertTeamSchedulingStrategy.LEAD_HANDOFF,
                    taskId = "lead-handoff-resume-task",
                    invokeMember = { member, _ ->
                        if (member.name == "文案官") {
                            "初步文案结论 [[DELEGATE:研究员 | 核验数据来源]]"
                        } else {
                            secondMemberStarted.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }
                )
            }
        }
        withTimeout(2_000) { secondMemberStarted.await() }
        withTimeout(2_000) {
            while (states.get("lead-handoff-resume-task")?.checkpoint?.contains("\"nextMemberName\":\"研究员\"") != true) {
                delay(10)
            }
        }
        tasks.pause("lead-handoff-resume-task")
        firstRun.join()
        assertEquals(AgentTaskStatus.PAUSED, tasks.state("lead-handoff-resume-task")?.status)

        val resumedCalls = mutableListOf<String>()
        val result = service.execute(
            teamId = "lead-handoff-resume",
            teamName = "主导交接团队",
            teamDescription = "先产出，再由研究员核验",
            task = "写一篇公众号文章",
            members = members,
            strategy = ExpertTeamSchedulingStrategy.LEAD_HANDOFF,
            taskId = "lead-handoff-resume-task",
            invokeMember = { member, _ ->
                resumedCalls += member.name
                "核验完成"
            }
        )
        assertEquals(listOf("研究员"), resumedCalls)
        assertEquals(2, result.successfulMembers)
        assertEquals(AgentTaskStatus.COMPLETED, tasks.state("lead-handoff-resume-task")?.status)
        tasks.close()
    }

    @Test
    fun projectPlanPausesAtCompletedWaveAndContinuesFromCheckpoint() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val tasks = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        val service = ExpertTeamExecutionCoordinator(tasks)
        val plan = ExpertProjectPlan(
            projectGoal = "完成项目研究",
            packages = listOf(
                ExpertWorkPackage("research", "研究员", "完成研究", acceptanceCriteria = "研究成果"),
                ExpertWorkPackage("summary", "策略师", "汇总研究", listOf("research"), "汇总成果")
            ),
            finalAcceptanceCriteria = "研究与汇总均完成"
        )
        val members = mapOf(
            "研究员" to ExpertTeamMemberTask("研究员", "负责研究"),
            "策略师" to ExpertTeamMemberTask("策略师", "负责汇总")
        )
        val pauseRequested = AtomicBoolean(false)
        val pauseReached = CompletableDeferred<Unit>()
        val resumeGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var result: ExpertProjectExecutionResult? = null

        try {
            val running = launch {
                result = service.executeProjectPlan(
                    teamId = "team-safe-pause",
                    teamName = "安全暂停团队",
                    teamDescription = "按依赖顺序执行",
                    plan = plan,
                    members = members,
                    projectId = "project-safe-pause",
                    taskId = "expert-project-safe-pause",
                    shouldPause = { pauseRequested.get() },
                    awaitResume = {
                        pauseReached.complete(Unit)
                        resumeGate.await()
                    },
                    invokeMember = { member, _ ->
                        calls += member.name
                        if (member.name == "研究员") {
                            pauseRequested.set(true)
                            "研究成果"
                        } else {
                            "汇总成果"
                        }
                    }
                )
            }

            withTimeout(2_000) { pauseReached.await() }
            assertEquals(AgentTaskStatus.PAUSED, tasks.state("expert-project-safe-pause")?.status)
            assertEquals(listOf("研究员"), calls)

            pauseRequested.set(false)
            resumeGate.complete(Unit)
            withTimeout(2_000) { running.join() }

            assertTrue(result?.isSuccessful == true)
            assertEquals(listOf("研究员", "策略师"), calls)
            assertEquals(AgentTaskStatus.COMPLETED, tasks.state("expert-project-safe-pause")?.status)
        } finally {
            tasks.close()
        }
    }


}
