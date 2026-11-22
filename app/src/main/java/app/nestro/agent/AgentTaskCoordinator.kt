package app.nestro.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    private val states: AgentTaskStateStore,
    private val traces: AgentTaskTraceStore? = null
) {
    private suspend fun trace(event: String, detail: String = "") {
        try {
            traces?.append(AgentTaskTrace(taskId = taskId, event = event, detail = detail.take(2_000)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Trace persistence must not fail the task, but cancellation must remain observable.
        }
    }

    suspend fun checkpoint(value: String, currentStep: Int? = null) {
        require(value.length <= 64_000) { "任务检查点超过 64 KB" }
        val state = states.get(taskId) ?: return
        states.put(state.copy(
            checkpoint = value,
            currentStep = currentStep ?: state.currentStep,
            updatedAt = System.currentTimeMillis()
        ))
        trace("checkpoint_saved", "步骤 " + (currentStep ?: state.currentStep))
    }

    suspend fun waitForUser(reason: String) {
        require(reason.isNotBlank() && reason.length <= 2_000)
        val state = states.get(taskId) ?: return
        states.put(state.copy(
            status = AgentTaskStatus.WAITING_FOR_USER,
            lastError = reason,
            updatedAt = System.currentTimeMillis()
        ))
        trace("waiting_for_user", reason)
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
    scope: CoroutineScope,
    private val traces: AgentTaskTraceStore? = null
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val transitionMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Deferred<Unit>>()
    private val requestedStops = ConcurrentHashMap<String, AgentTaskStatus>()

    private suspend fun trace(taskId: String, event: String, detail: String = "") {
        try {
            traces?.append(AgentTaskTrace(taskId = taskId, event = event, detail = detail.take(2_000)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Trace persistence must not fail the task, but cancellation must remain observable.
        }
    }

    private suspend fun <T> withTransitionLock(block: suspend () -> T): T {
        transitionMutex.lock()
        try {
            return block()
        } finally {
            transitionMutex.unlock()
        }
    }

    init {
        scope.launch {
            for (taskId in queue) executeQueued(taskId)
        }
    }

    fun close() {
        queue.close()
    }

    suspend fun enqueue(taskId: String, owner: String, checkpoint: String? = null): AgentTaskState {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        val queued = withTransitionLock {
            val existing = states.get(taskId)
            if (existing != null) require(existing.owner == owner) { "任务 ID 已被其他模块占用" }
            if (existing != null && existing.status in setOf(
                    AgentTaskStatus.QUEUED,
                    AgentTaskStatus.RUNNING,
                    AgentTaskStatus.COMPLETED
                )) return@withTransitionLock existing
            val next = (existing ?: AgentTaskState(taskId, owner)).copy(
                owner = owner,
                status = AgentTaskStatus.QUEUED,
                checkpoint = checkpoint?.take(64_000) ?: existing?.checkpoint.orEmpty(),
                lastError = "",
                updatedAt = System.currentTimeMillis()
            )
            states.put(next)
            next
        }
        if (queued.status == AgentTaskStatus.QUEUED) {
            queue.send(taskId)
            trace(taskId, "queued", owner)
        }
        return queued
    }

    suspend fun resume(taskId: String): AgentTaskState? {
        val queued = withTransitionLock {
            val existing = states.get(taskId) ?: return@withTransitionLock null
            if (existing.status !in setOf(
                    AgentTaskStatus.PAUSED,
                    AgentTaskStatus.FAILED,
                    AgentTaskStatus.WAITING_FOR_USER
                )) return@withTransitionLock existing
            val next = existing.copy(
                status = AgentTaskStatus.QUEUED,
                lastError = "",
                updatedAt = System.currentTimeMillis()
            )
            states.put(next)
            next
        } ?: return null
        if (queued.status == AgentTaskStatus.QUEUED) {
            queue.send(taskId)
            trace(taskId, "resumed")
        }
        return queued
    }

    /**
     * Reattaches a persisted RUNNING task to the in-memory consumer when its durable
     * scheduler (for example WorkManager) starts after process recreation.
     * An already-active task is left untouched to prevent duplicate execution.
     */
    suspend fun requeueRunningTaskIfIdle(taskId: String): AgentTaskState? {
        val shouldQueue = withTransitionLock {
            val state = states.get(taskId) ?: return@withTransitionLock false
            if (state.status != AgentTaskStatus.RUNNING || activeJobs.containsKey(taskId)) {
                return@withTransitionLock false
            }
            states.put(state.copy(
                status = AgentTaskStatus.QUEUED,
                lastError = "",
                updatedAt = System.currentTimeMillis()
            ))
            true
        }
        if (shouldQueue) {
            queue.send(taskId)
            trace(taskId, "reattached_by_external_scheduler")
        }
        return states.get(taskId)
    }

    suspend fun <T> runInline(
        taskId: String,
        owner: String,
        initialCheckpoint: String = "",
        handler: suspend (AgentTaskExecutionContext) -> T
    ): T {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        // Stable task-scoped handler identity allows the owning feature to re-register
        // its handler and resume a durable checkpoint after process recreation.
        val handlerId = owner.take(32) + "-" + taskId.hashCode().toUInt().toString(16)
        val existing = states.get(taskId)
        if (existing != null) {
            require(existing.owner == handlerId) { "runInline 任务 ID 已被其他任务占用" }
            require(existing.status !in setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) {
                "任务当前状态为 ${existing.status}，不能重复启动"
            }
        }
        val completion = CompletableDeferred<T>()
        handlers.register(handlerId) { context ->
            try {
                completion.complete(handler(context))
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
                throw error
            }
        }
        try {
            val queued = enqueue(taskId, handlerId, if (existing == null) initialCheckpoint else null)
            check(queued.owner == handlerId) { "runInline 任务 ID 已被占用" }
            return completion.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val latest = states.get(taskId)
                if (!requestedStops.containsKey(taskId) && latest?.status !in setOf(
                        AgentTaskStatus.PAUSED,
                        AgentTaskStatus.CANCELLED,
                        AgentTaskStatus.COMPLETED,
                        AgentTaskStatus.WAITING_FOR_USER
                    )) {
                    cancel(taskId)
                }
            }
            throw cancelled
        } finally {
            handlers.unregister(handlerId)
        }
    }

    /** Read-only durable task snapshot for feature-specific checkpoint restoration. */
    suspend fun state(taskId: String): AgentTaskState? = states.get(taskId)

    suspend fun pause(taskId: String) {
        stop(taskId, AgentTaskStatus.PAUSED)
    }

    suspend fun cancel(taskId: String) {
        stop(taskId, AgentTaskStatus.CANCELLED)
    }

    suspend fun recoverAfterProcessRestart(externallyScheduledOwners: Set<String> = emptySet()): List<AgentTaskState> {
        val recovered = mutableListOf<AgentTaskState>()
        val queuedIds = mutableListOf<String>()
        states.recoverable().forEach { state ->
            val next = withTransitionLock {
                val latest = states.get(state.taskId) ?: return@withTransitionLock null
                when (latest.status) {
                    AgentTaskStatus.RUNNING -> {
                        if (latest.owner in externallyScheduledOwners) {
                            // Let the durable scheduler reattach execution after process restart.
                            latest
                        } else {
                            val paused = latest.copy(
                                status = AgentTaskStatus.PAUSED,
                                lastError = "应用曾在任务执行期间退出；请核对当前状态后继续",
                                updatedAt = System.currentTimeMillis()
                            )
                            states.put(paused)
                            paused
                        }
                    }
                    AgentTaskStatus.QUEUED -> {
                        if (latest.owner in externallyScheduledOwners) {
                            // Durable schedulers (for example WorkManager) own dispatch.
                            // Keep the task queued for that scheduler instead of executing
                            // it directly from the application-scope recovery worker.
                            latest
                        } else if (handlers.find(latest.owner) != null) {
                            queuedIds += latest.taskId
                            latest
                        } else {
                            val paused = latest.copy(
                                status = AgentTaskStatus.PAUSED,
                                lastError = "任务处理器尚未恢复；请重新打开对应功能后再继续",
                                updatedAt = System.currentTimeMillis()
                            )
                            states.put(paused)
                            paused
                        }
                    }
                    AgentTaskStatus.PAUSED,
                    AgentTaskStatus.WAITING_FOR_USER,
                    AgentTaskStatus.FAILED -> latest
                    else -> null
                }
            }
            if (next != null) recovered += next
        }
        queuedIds.forEach {
            queue.send(it)
            trace(it, "requeued_after_restart")
        }
        recovered.filter { it.status == AgentTaskStatus.PAUSED }.forEach {
            trace(it.taskId, "interrupted", it.lastError)
        }
        return recovered
    }

    private suspend fun stop(taskId: String, status: AgentTaskStatus) {
        // Revoke task-scoped capabilities and credentials before the task can continue.
        AgentToolLeaseRegistry.revokeTask(taskId)
        AgentCredentialLeaseRegistry.revokeTask(taskId)
        withTransitionLock {
            val state = states.get(taskId) ?: return@withTransitionLock
            if (state.status in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) return@withTransitionLock
            val job = activeJobs[taskId]
            if (job == null) {
                states.put(state.copy(status = status, updatedAt = System.currentTimeMillis()))
            } else {
                requestedStops[taskId] = status
                job.cancel(CancellationException(if (status == AgentTaskStatus.PAUSED) "用户暂停任务" else "用户取消任务"))
            }
        }
        trace(taskId, if (status == AgentTaskStatus.PAUSED) "pause_requested" else "cancel_requested")
    }

    private suspend fun executeQueued(taskId: String) {
        val initial = states.get(taskId) ?: return
        if (initial.status != AgentTaskStatus.QUEUED) return
        val handler = handlers.find(initial.owner)
        if (handler == null) {
            withTransitionLock {
                val latest = states.get(taskId) ?: return@withTransitionLock
                if (latest.status == AgentTaskStatus.QUEUED) {
                    states.put(latest.copy(
                        status = AgentTaskStatus.FAILED,
                        lastError = "没有注册任务处理器：" + initial.owner,
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            }
            trace(taskId, "failed", "没有注册任务处理器：" + initial.owner)
            return
        }
        supervisorScope {
            val job = async(start = CoroutineStart.LAZY) {
                handler.execute(AgentTaskExecutionContext(taskId, states, traces))
            }
            val started = withTransitionLock {
                val latest = states.get(taskId)
                if (latest == null || latest.status != AgentTaskStatus.QUEUED) {
                    false
                } else {
                    states.put(latest.copy(
                        status = AgentTaskStatus.RUNNING,
                        lastError = "",
                        updatedAt = System.currentTimeMillis()
                    ))
                    activeJobs[taskId] = job
                    job.start()
                    true
                }
            }
            if (!started) {
                job.cancel()
                return@supervisorScope
            }
            trace(taskId, "started", initial.owner)
            try {
                job.await()
                withTransitionLock {
                    val latest = states.get(taskId) ?: initial
                    val requested = requestedStops.remove(taskId)
                    when {
                        requested != null -> states.put(latest.copy(
                            status = requested,
                            lastError = if (requested == AgentTaskStatus.PAUSED) "任务已暂停，可从检查点恢复" else "任务已取消",
                            updatedAt = System.currentTimeMillis()
                        ))
                        latest.status in setOf(
                            AgentTaskStatus.WAITING_FOR_USER,
                            AgentTaskStatus.PAUSED,
                            AgentTaskStatus.FAILED,
                            AgentTaskStatus.CANCELLED,
                            AgentTaskStatus.COMPLETED
                        ) -> Unit
                        else -> states.put(latest.copy(
                            status = AgentTaskStatus.COMPLETED,
                            lastError = "",
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                }
                val finalState = states.get(taskId)
                if (finalState?.status == AgentTaskStatus.COMPLETED) trace(taskId, "completed")
                if (finalState?.status == AgentTaskStatus.WAITING_FOR_USER) trace(taskId, "waiting_for_user", finalState.lastError)
            } catch (cancelled: CancellationException) {
                val requested = withContext(NonCancellable) {
                    withTransitionLock {
                        val stopStatus = requestedStops.remove(taskId)
                        if (stopStatus != null) {
                            val latest = states.get(taskId) ?: initial
                            states.put(latest.copy(
                                status = stopStatus,
                                lastError = if (stopStatus == AgentTaskStatus.PAUSED) "任务已暂停，可从检查点恢复" else "任务已取消",
                                updatedAt = System.currentTimeMillis()
                            ))
                        }
                        stopStatus
                    }
                }
                if (requested != null) {
                    trace(taskId, if (requested == AgentTaskStatus.CANCELLED) "cancelled" else "paused")
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
                    trace(taskId, "paused", "任务执行被中断")
                }
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    val latest = states.get(taskId) ?: initial
                    val message = error.message?.take(2_000) ?: "任务执行失败"
                    states.put(latest.copy(
                        status = AgentTaskStatus.FAILED,
                        lastError = message,
                        updatedAt = System.currentTimeMillis()
                    ))
                    trace(taskId, "failed", message)
                }
            } finally {
                AgentToolLeaseRegistry.revokeTask(taskId)
                AgentCredentialLeaseRegistry.revokeTask(taskId)
                withTransitionLock { activeJobs.remove(taskId, job) }
            }
        }
    }
}
