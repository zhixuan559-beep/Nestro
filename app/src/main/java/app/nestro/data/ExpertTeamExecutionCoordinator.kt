package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import java.util.UUID

enum class ExpertTeamExecutionMode {
    /** User assigns each member a work package; independent work runs concurrently. */
    MANUAL_PARALLEL,
    /** The lead expert may hand off bounded subtasks to other members. */
    SEQUENTIAL_DELEGATION
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
        mode: ExpertTeamExecutionMode = ExpertTeamExecutionMode.SEQUENTIAL_DELEGATION,
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
                    relatedMemberName = relatedMemberName, status = status, message = message.take(8_000)
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

        val result = when (mode) {
            ExpertTeamExecutionMode.MANUAL_PARALLEL -> ExpertTeamEngine.run(
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
            ExpertTeamExecutionMode.SEQUENTIAL_DELEGATION -> ExpertTeamEngine.runCollaborative(
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
}
