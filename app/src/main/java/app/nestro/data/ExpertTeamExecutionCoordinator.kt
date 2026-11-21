package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import java.util.UUID

enum class ExpertTeamSchedulingStrategy {
    /** User assigns each member a work package; independent work runs concurrently. */
    PARALLEL_WORK_PACKAGES,
    /** The lead expert may hand off bounded subtasks to other members. */
    LEAD_HANDOFF
}

/**
 * Owns expert-team execution and its shared AgentTaskCoordinator lifecycle.
 * Compose callers provide model invocation and UI progress callbacks; the
 * coordinator owns task registration, checkpoints, retries, and cancellation.
 */
class ExpertTeamExecutionCoordinator(
    private val taskCoordinator: AgentTaskCoordinator
) {
    suspend fun execute(
        teamId: String,
        teamName: String,
        teamDescription: String,
        task: String,
        members: List<ExpertTeamMemberTask>,
        strategy: ExpertTeamSchedulingStrategy = ExpertTeamSchedulingStrategy.LEAD_HANDOFF,
        taskId: String = UUID.randomUUID().toString(),
        projectId: String = "default",
        shouldStop: () -> Boolean = { false },
        onMember: suspend (String, String) -> Unit = { _, _ -> },
        eventSink: ExpertTeamEventSink? = null,
        invokeMember: suspend (ExpertTeamMemberTask, String) -> String,
        toolCatalog: AgentToolCatalog? = null,
        invokeMemberWithTools: (suspend (ExpertTeamMemberTask, String, AgentToolDiscovery?) -> String)? = null
    ): ExpertTeamRunResult = taskCoordinator.runInline(
        taskId = taskId,
        owner = "expert_team",
        initialCheckpoint = "team:" + teamId + ":queued"
    ) { taskContext ->
        taskContext.checkpoint("team:" + teamId + ":running", 0)
        var eventSequence = 0L
        suspend fun emit(
            type: String,
            memberName: String = "",
            relatedMemberName: String = "",
            status: String = "",
            message: String = ""
        ) {
            eventSink?.append(
                ExpertTeamCollaborationEvent(
                    taskId = taskId, projectId = projectId, teamId = teamId,
                    sequence = eventSequence++, type = type, memberName = memberName,
                    relatedMemberName = relatedMemberName, status = status, message = message.take(3_000)
                )
            )
        }
        emit("team_started", status = "running", message = task.take(2_000))

        suspend fun invokeWithLease(member: ExpertTeamMemberTask, prompt: String): String {
            val lease = AgentCapabilityRegistry.openLease(
                taskId = taskId,
                owner = "expert_team",
                operation = member.name,
                catalog = toolCatalog
            )
            return try {
                if (lease != null && invokeMemberWithTools == null) {
                    error("专家成员 ${member.name} 声明了工具能力，但未提供工具感知的模型调用适配器")
                }
                invokeMemberWithTools?.invoke(member, prompt, lease?.access)
                    ?: invokeMember(member, prompt)
            } finally {
                lease?.let { AgentToolLeaseRegistry.release(it.id) }
            }
        }

        val result = when (strategy) {
            ExpertTeamSchedulingStrategy.PARALLEL_WORK_PACKAGES -> ExpertTeamEngine.run(
                teamName = teamName,
                teamDescription = teamDescription,
                task = task,
                members = members,
                maxParallel = 4,
                shouldStop = shouldStop,
                onMember = { name, status ->
                    onMember(name, status)
                    emit(when (status) { "running" -> "member_started"; "retrying" -> "member_retrying"; "success" -> "member_completed"; else -> "member_failed" },
                        memberName = name, status = status)
                },
                invoke = ::invokeWithLease
            )
            ExpertTeamSchedulingStrategy.LEAD_HANDOFF -> ExpertTeamEngine.runCollaborative(
                teamName = teamName,
                teamDescription = teamDescription,
                task = task,
                members = members,
                timeoutMillis = 45_000,
                shouldStop = shouldStop,
                onMember = { name, status ->
                    onMember(name, status)
                    emit(when (status) { "running" -> "member_started"; "retrying" -> "member_retrying"; "success" -> "member_completed"; else -> "member_failed" },
                        memberName = name, status = status)
                },
                onHandoff = { from, to, subtask, handoff ->
                    emit("member_handoff", memberName = from, relatedMemberName = to,
                        status = "requested", message = "子任务：" + subtask + "\n交接内容：" + handoff)
                },
                invoke = ::invokeWithLease
            )
        }
        result.members.filter { it.status == "success" }.forEach { member ->
            emit("artifact_submitted", memberName = member.name, status = "submitted", message = member.output)
        }
        emit("team_completed", status = if (result.failedMembers == 0) "success" else "partial",
            message = "成功 " + result.successfulMembers + " 人，失败 " + result.failedMembers + " 人")
        taskContext.checkpoint("team:" + teamId + ":finished", result.members.size)
        result.copy(projectId = projectId)
    }
    suspend fun executeProjectPlan(
        teamId: String,
        teamName: String,
        teamDescription: String,
        plan: ExpertProjectPlan,
        members: Map<String, ExpertTeamMemberTask>,
        projectId: String,
        taskId: String = UUID.randomUUID().toString(),
        shouldStop: () -> Boolean = { false },
        eventSink: ExpertTeamEventSink? = null,
        onPackage: suspend (String, String, String) -> Unit = { _, _, _ -> },
        invokeMember: suspend (ExpertTeamMemberTask, String) -> String,
        toolCatalog: AgentToolCatalog? = null,
        invokeMemberWithTools: (suspend (ExpertTeamMemberTask, String, AgentToolDiscovery?) -> String)? = null
    ): ExpertProjectExecutionResult = taskCoordinator.runInline(
        taskId = taskId,
        owner = "expert_team_project",
        initialCheckpoint = "team:" + teamId + ":project_queued"
    ) { taskContext ->
        taskContext.checkpoint("team:" + teamId + ":project_running", 0)
        var sequence = 0L
        suspend fun emit(type: String, member: String = "", status: String = "", message: String = "") {
            eventSink?.append(
                ExpertTeamCollaborationEvent(
                    taskId = taskId, projectId = projectId, teamId = teamId,
                    sequence = sequence++, type = type, memberName = member,
                    status = status, message = message.take(3_000)
                )
            )
        }
        emit("team_started", status = "running", message = plan.projectGoal.take(2_000))
        val result = ExpertProjectPlanExecutor.execute(
            plan = plan,
            availableMembers = members.keys,
            shouldStop = shouldStop,
            invoke = { memberName, instruction ->
                val member = members[memberName] ?: error("专家不在当前团队中：$memberName")
                val lease = AgentCapabilityRegistry.openLease(
                    taskId = taskId, owner = "expert_team_project",
                    operation = member.name, catalog = toolCatalog
                )
                try {
                    if (lease != null && invokeMemberWithTools == null) {
                        error("专家成员 ${member.name} 声明了工具能力，但未提供工具感知的模型调用适配器")
                    }
                    invokeMemberWithTools?.invoke(member, instruction, lease?.access)
                        ?: invokeMember(member, instruction)
                } finally {
                    lease?.let { AgentToolLeaseRegistry.release(it.id) }
                }
            },
            onPackage = { packageId, status, detail ->
                onPackage(packageId, status, detail)
                val packageInfo = plan.packages.firstOrNull { it.id == packageId }
                val eventType = when (status) {
                    "running" -> "member_started"
                    "success" -> "artifact_submitted"
                    "failed" -> "member_failed"
                    else -> "member_message"
                }
                emit(eventType, member = packageInfo?.memberName.orEmpty(), status = status,
                    message = "工作包 $packageId：$detail")
            },
            onHandoff = { fromPackage, toPackage, fromMember, toMember, artifact ->
                emit(
                    "member_handoff",
                    member = fromMember,
                    status = "delivered",
                    message = "交接至 $toMember（$toPackage），来源工作包 $fromPackage：\n$artifact"
                )
            }
        )
        emit("team_completed", status = if (result.isSuccessful) "success" else "partial",
            message = "工作包完成：${result.packageResults.count { it.status == "success" }}/${plan.packages.size}")
        taskContext.checkpoint("team:" + teamId + ":project_finished", result.packageResults.size)
        result
    }

}
