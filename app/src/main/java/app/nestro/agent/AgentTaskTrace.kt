package app.nestro.agent

import java.util.UUID
import kotlinx.coroutines.sync.withLock

data class AgentTaskTrace(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val event: String,
    val detail: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(event.isNotBlank() && event.length <= 80)
        require(detail.length <= 2_000)
    }
}

interface AgentTaskTraceStore {
    suspend fun append(trace: AgentTaskTrace)
    suspend fun recent(taskId: String, limit: Int = 100): List<AgentTaskTrace>
}

class InMemoryAgentTaskTraceStore : AgentTaskTraceStore {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val traces = mutableListOf<AgentTaskTrace>()

    override suspend fun append(trace: AgentTaskTrace) {
        mutex.withLock { traces += trace }
    }

    override suspend fun recent(taskId: String, limit: Int): List<AgentTaskTrace> =
        mutex.withLock { traces.filter { it.taskId == taskId }.takeLast(limit.coerceIn(1, 500)) }
}
