package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import java.util.UUID

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
        taskId: String = UUID.randomUUID().toString(),
        shouldStop: () -> Boolean = { false },
        onMember: suspend (String, String) -> Unit = { _, _ -> },
        invokeMember: suspend (ExpertTeamMemberTask, String) -> String,
        toolCatalog: AgentToolCatalog? = null,
        invokeMemberWithTools: (suspend (ExpertTeamMemberTask, String, AgentToolDiscovery?) -> String)? = null
    ): ExpertTeamRunResult = taskCoordinator.runInline(
        taskId = taskId,
        owner = "expert_team",
        initialCheckpoint = "team:" + teamId + ":queued"
    ) { taskContext ->
        taskContext.checkpoint("team:" + teamId + ":running", 0)
        val result = ExpertTeamEngine.runCollaborative(
            teamName = teamName,
            teamDescription = teamDescription,
            task = task,
            members = members,
            timeoutMillis = 45_000,
            shouldStop = shouldStop,
            onMember = onMember,
            invoke = { member, prompt ->
                val lease = AgentCapabilityRegistry.openLease(
                    taskId = taskId,
                    owner = "expert_team",
                    operation = member.name,
                    catalog = toolCatalog
                )
                try {
                    if (lease != null && invokeMemberWithTools == null) {
                        error("专家成员 ${member.name} 声明了工具能力，但未提供工具感知的模型调用适配器")
                    }
                    invokeMemberWithTools?.invoke(member, prompt, lease?.access)
                        ?: invokeMember(member, prompt)
                } finally {
                    lease?.let { AgentToolLeaseRegistry.release(it.id) }
                }
            }
        )
        taskContext.checkpoint("team:" + teamId + ":finished", result.members.size)
        result
    }
}
