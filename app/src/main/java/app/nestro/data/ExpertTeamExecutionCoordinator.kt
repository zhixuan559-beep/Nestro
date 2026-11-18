package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
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
        invokeMember: suspend (ExpertTeamMemberTask, String) -> String
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
            invoke = invokeMember
        )
        taskContext.checkpoint("team:" + teamId + ":finished", result.members.size)
        result
    }
}
