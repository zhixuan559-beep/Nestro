package app.nestro.agent

import app.nestro.data.AgentTaskStateEntity
import app.nestro.data.NestroDao

class RoomAgentTaskStateStore(
    private val dao: NestroDao
) : AgentTaskStateStore {
    private fun AgentTaskStateEntity.toState() = AgentTaskState(
        taskId = taskId,
        owner = owner,
        status = runCatching { AgentTaskStatus.valueOf(status) }.getOrDefault(AgentTaskStatus.PAUSED),
        currentStep = currentStep.coerceAtLeast(0),
        checkpoint = checkpoint,
        lastError = lastError,
        updatedAt = updatedAt,
        projectId = projectId,
        conversationId = conversationId,
        kind = kind,
        capabilityId = capabilityId,
        title = title,
        input = input,
        resultSummary = resultSummary,
        idempotencyKey = idempotencyKey,
        attempts = attempts,
        maxRetries = maxRetries,
        availableAt = availableAt,
        startedAt = startedAt,
        finishedAt = finishedAt
    )

    override suspend fun get(taskId: String): AgentTaskState? =
        dao.agentTaskState(taskId)?.toState()

    override suspend fun getByIdempotencyKey(key: String): AgentTaskState? =
        dao.agentTaskStateByIdempotencyKey(key)?.toState()

    override suspend fun put(state: AgentTaskState) {
        dao.putAgentTaskState(state.toEntity())
    }

    override suspend fun putIfAbsent(state: AgentTaskState): AgentTaskState =
        dao.putAgentTaskStateIfAbsent(state.toEntity()).toState()

    private fun AgentTaskState.toEntity() = AgentTaskStateEntity(
        taskId = taskId,
        owner = owner,
        status = status.name,
        currentStep = currentStep,
        checkpoint = checkpoint,
        lastError = lastError,
        updatedAt = updatedAt,
        projectId = projectId,
        conversationId = conversationId,
        kind = kind,
        capabilityId = capabilityId,
        title = title,
        input = input,
        resultSummary = resultSummary,
        idempotencyKey = idempotencyKey,
        attempts = attempts,
        maxRetries = maxRetries,
        availableAt = availableAt,
        startedAt = startedAt,
        finishedAt = finishedAt
    )

    override suspend fun recoverable(): List<AgentTaskState> =
        dao.recoverableAgentTaskStates().map { it.toState() }
}
