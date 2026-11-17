package app.nestro.agent

import app.nestro.data.AgentTaskTraceEntity
import app.nestro.data.NestroDao

class RoomAgentTaskTraceStore(
    private val dao: NestroDao
) : AgentTaskTraceStore {
    override suspend fun append(trace: AgentTaskTrace) {
        dao.insertAgentTaskTrace(
            AgentTaskTraceEntity(
                id = trace.id,
                taskId = trace.taskId,
                event = trace.event,
                detail = trace.detail,
                createdAt = trace.createdAt
            )
        )
        dao.pruneAgentTaskTraces(trace.taskId, 500)
    }

    override suspend fun recent(taskId: String, limit: Int): List<AgentTaskTrace> =
        dao.recentAgentTaskTraces(taskId, limit.coerceIn(1, 500)).asReversed().map { row ->
            AgentTaskTrace(
                id = row.id,
                taskId = row.taskId,
                event = row.event,
                detail = row.detail,
                createdAt = row.createdAt
            )
        }
}
