package app.nestro.agent

import app.nestro.data.AgentTaskStateEntity
import app.nestro.data.NestroDao

class RoomAgentTaskStateStore(
    private val dao: NestroDao
) : AgentTaskStateStore {
    override suspend fun get(taskId: String): AgentTaskState? {
        val row = dao.agentTaskState(taskId) ?: return null
        val status = runCatching { AgentTaskStatus.valueOf(row.status) }
            .getOrDefault(AgentTaskStatus.PAUSED)
        return AgentTaskState(
            taskId = row.taskId,
            owner = row.owner,
            status = status,
            currentStep = row.currentStep.coerceAtLeast(0),
            checkpoint = row.checkpoint,
            lastError = row.lastError,
            updatedAt = row.updatedAt
        )
    }

    override suspend fun put(state: AgentTaskState) {
        dao.putAgentTaskState(
            AgentTaskStateEntity(
                taskId = state.taskId,
                owner = state.owner,
                status = state.status.name,
                currentStep = state.currentStep,
                checkpoint = state.checkpoint,
                lastError = state.lastError,
                updatedAt = state.updatedAt
            )
        )
    }

    override suspend fun recoverable(): List<AgentTaskState> =
        dao.recoverableAgentTaskStates().map { row ->
            AgentTaskState(
                taskId = row.taskId,
                owner = row.owner,
                status = runCatching { AgentTaskStatus.valueOf(row.status) }
                    .getOrDefault(AgentTaskStatus.PAUSED),
                currentStep = row.currentStep.coerceAtLeast(0),
                checkpoint = row.checkpoint,
                lastError = row.lastError,
                updatedAt = row.updatedAt
            )
        }
}
