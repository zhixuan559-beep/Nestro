package app.nestro.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

fun interface AgentTaskHandler {
    suspend fun execute(context: AgentTaskExecutionContext)
}

class AgentTaskHandlerRegistry {
    private val handlers = ConcurrentHashMap<String, AgentTaskHandler>()

    fun register(owner: String, handler: AgentTaskHandler) {
        require(owner.isNotBlank() && owner.length <= 80)
        handlers[owner] = handler
    }

    fun unregister(owner: String) {
        handlers.remove(owner)
    }

    fun find(owner: String): AgentTaskHandler? = handlers[owner]
}

class AgentTaskExecutionContext internal constructor(
    val taskId: String,
    private val states: AgentTaskStateStore
) {
    suspend fun checkpoint(value: String, currentStep: Int? = null) {
        require(value.length <= 64_000) { "任务检查点超过 64 KB" }
        val state = states.get(taskId) ?: return
        states.put(state.copy(
            checkpoint = value,
            currentStep = currentStep ?: state.currentStep,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun waitForUser(reason: String) {
        require(reason.isNotBlank() && reason.length <= 2_000)
        val state = states.get(taskId) ?: return
        states.put(state.copy(
            status = AgentTaskStatus.WAITING_FOR_USER,
            lastError = reason,
            updatedAt = System.currentTimeMillis()
        ))
    }
}

/**
 * Persistent single-consumer task queue shared by agent features.
 * Task IDs are idempotency keys: enqueueing an existing queued, running or
 * completed task never starts a duplicate execution.
 */
class AgentTaskCoordinator(
    private val states: AgentTaskStateStore,
    private val handlers: AgentTaskHandlerRegistry,
    scope: CoroutineScope
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val activeJobs = ConcurrentHashMap<String, Deferred<Unit>>()
    private val requestedStops = ConcurrentHashMap<String, AgentTaskStatus>()

    init {
        scope.launch {
            for (taskId in queue) executeQueued(taskId)
        }
    }

    suspend fun enqueue(taskId: String, owner: String, checkpoint: String = ""): AgentTaskState {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        val existing = states.get(taskId)
        if (existing != null && existing.status in setOf(
                AgentTaskStatus.QUEUED,
                AgentTaskStatus.RUNNING,
                AgentTaskStatus.COMPLETED
            )) return existing
        val queued = (existing ?: AgentTaskState(taskId, owner)).copy(
            owner = owner,
            status = AgentTaskStatus.QUEUED,
            checkpoint = checkpoint.take(64_000),
            lastError = "",
            updatedAt = System.currentTimeMillis()
        )
        states.put(queued)
        queue.send(taskId)
        return queued
    }

    suspend fun resume(taskId: String): AgentTaskState? {
        val existing = states.get(taskId) ?: return null
        if (existing.status !in setOf(
                AgentTaskStatus.PAUSED,
                AgentTaskStatus.FAILED,
                AgentTaskStatus.WAITING_FOR_USER
            )) return existing
        val queued = existing.copy(
            status = AgentTaskStatus.QUEUED,
            lastError = "",
            updatedAt = System.currentTimeMillis()
        )
        states.put(queued)
        queue.send(taskId)
        return queued
    }

    suspend fun pause(taskId: String) {
        stop(taskId, AgentTaskStatus.PAUSED)
    }

    suspend fun cancel(taskId: String) {
        stop(taskId, AgentTaskStatus.CANCELLED)
    }

    suspend fun recoverAfterProcessRestart(): List<AgentTaskState> {
        val recovered = mutableListOf<AgentTaskState>()
        states.recoverable().forEach { state ->
            when (state.status) {
                AgentTaskStatus.RUNNING -> {
                    val paused = state.copy(
                        status = AgentTaskStatus.PAUSED,
                        lastError = "应用曾在任务执行期间退出；请核对当前状态后继续",
                        updatedAt = System.currentTimeMillis()
                    )
                    states.put(paused)
                    recovered += paused
                }
                AgentTaskStatus.QUEUED -> {
                    queue.send(state.taskId)
                    recovered += state
                }
                AgentTaskStatus.PAUSED -> recovered += state
                else -> Unit
            }
        }
        return recovered
    }

    private suspend fun stop(taskId: String, status: AgentTaskStatus) {
        val state = states.get(taskId) ?: return
        if (state.status in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) return
        val job = activeJobs[taskId]
        if (job == null) {
            states.put(state.copy(status = status, updatedAt = System.currentTimeMillis()))
        } else {
            requestedStops[taskId] = status
            job.cancel(CancellationException(if (status == AgentTaskStatus.PAUSED) "用户暂停任务" else "用户取消任务"))
        }
    }

    private suspend fun executeQueued(taskId: String) {
        val initial = states.get(taskId) ?: return
        if (initial.status != AgentTaskStatus.QUEUED) return
        val handler = handlers.find(initial.owner)
        if (handler == null) {
            states.put(initial.copy(
                status = AgentTaskStatus.FAILED,
                lastError = "没有注册任务处理器：${initial.owner}",
                updatedAt = System.currentTimeMillis()
            ))
            return
        }
        states.put(initial.copy(status = AgentTaskStatus.RUNNING, lastError = "", updatedAt = System.currentTimeMillis()))
        supervisorScope {
            val job = async {
                handler.execute(AgentTaskExecutionContext(taskId, states))
            }
            activeJobs[taskId] = job
            try {
                job.await()
                val latest = states.get(taskId) ?: initial
                if (latest.status != AgentTaskStatus.WAITING_FOR_USER) {
                    states.put(latest.copy(
                        status = AgentTaskStatus.COMPLETED,
                        lastError = "",
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            } catch (cancelled: CancellationException) {
                val requested = requestedStops.remove(taskId)
                if (requested != null) {
                    withContext(NonCancellable) {
                        val latest = states.get(taskId) ?: initial
                        states.put(latest.copy(
                            status = requested,
                            lastError = if (requested == AgentTaskStatus.PAUSED) "任务已暂停，可从检查点恢复" else "任务已取消",
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                } else if (!currentCoroutineContext().isActive) {
                    throw cancelled
                } else {
                    withContext(NonCancellable) {
                        val latest = states.get(taskId) ?: initial
                        states.put(latest.copy(
                            status = AgentTaskStatus.PAUSED,
                            lastError = "任务执行被中断，可从检查点恢复",
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    val latest = states.get(taskId) ?: initial
                    states.put(latest.copy(
                        status = AgentTaskStatus.FAILED,
                        lastError = error.message?.take(2_000) ?: "任务执行失败",
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            } finally {
                activeJobs.remove(taskId)
            }
        }
    }
}
