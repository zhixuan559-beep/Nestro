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
import kotlinx.coroutines.delay
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

    suspend fun waitForUser(reason: String) = transition(AgentTaskStatus.WAITING_FOR_USER, reason)

    suspend fun pause(reason: String) = transition(AgentTaskStatus.PAUSED, reason)

    suspend fun fail(reason: String) = transition(AgentTaskStatus.FAILED, reason)

    suspend fun cancel(reason: String = "用户取消任务") = transition(AgentTaskStatus.CANCELLED, reason)

    suspend fun transition(
        status: AgentTaskStatus,
        reason: String = "",
        currentStep: Int? = null,
        resultSummary: String? = null
    ) {
        require(status in setOf(
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.WAITING_FOR_USER,
            AgentTaskStatus.PAUSED,
            AgentTaskStatus.FAILED,
            AgentTaskStatus.COMPLETED,
            AgentTaskStatus.CANCELLED
        )) { "执行上下文不能直接设置创建、排队或重试等待状态" }
        require(reason.length <= 2_000)
        val state = states.get(taskId) ?: return
        val now = System.currentTimeMillis()
        states.put(state.copy(
            status = status,
            currentStep = currentStep ?: state.currentStep,
            lastError = reason,
            resultSummary = resultSummary?.take(8_000) ?: state.resultSummary,
            updatedAt = now,
            finishedAt = if (status in setOf(AgentTaskStatus.FAILED, AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) now else state.finishedAt
        ))
        val event = when (status) {
            AgentTaskStatus.WAITING_FOR_USER -> "waiting_for_user"
            AgentTaskStatus.PAUSED -> "paused"
            AgentTaskStatus.FAILED -> "failed"
            AgentTaskStatus.COMPLETED -> "completed"
            AgentTaskStatus.CANCELLED -> "cancelled"
            else -> "state_changed"
        }
        trace(event, reason)
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
    private val scope: CoroutineScope,
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

    suspend fun enqueue(
        taskId: String,
        owner: String,
        checkpoint: String? = null,
        metadata: AgentTaskMetadata = AgentTaskMetadata(),
        idempotencyKey: String? = null,
        maxRetries: Int = 2
    ): AgentTaskState {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        require(idempotencyKey == null || (idempotencyKey.isNotBlank() && idempotencyKey.length <= 240))
        require(maxRetries in 0..5)
        val (queued, shouldQueue) = withTransitionLock {
            val keyed = idempotencyKey?.let { states.getByIdempotencyKey(it) }
            if (keyed != null) {
                require(keyed.owner == owner) { "幂等键已被其他模块占用" }
                return@withTransitionLock keyed to false
            }
            val existing = states.get(taskId)
            if (existing != null) require(existing.owner == owner) { "任务 ID 已被其他模块占用" }
            if (existing != null && existing.status in setOf(
                    AgentTaskStatus.QUEUED,
                    AgentTaskStatus.RUNNING,
                    AgentTaskStatus.COMPLETED,
                    AgentTaskStatus.CANCELLED
                )) return@withTransitionLock existing to false
            val now = System.currentTimeMillis()
            val next = (existing ?: AgentTaskState(taskId, owner)).copy(
                owner = owner,
                status = AgentTaskStatus.QUEUED,
                checkpoint = checkpoint?.take(64_000) ?: existing?.checkpoint.orEmpty(),
                lastError = "",
                projectId = metadata.projectId.ifBlank { existing?.projectId.orEmpty() },
                conversationId = metadata.conversationId.ifBlank { existing?.conversationId.orEmpty() },
                kind = metadata.kind.ifBlank { existing?.kind.orEmpty() },
                capabilityId = metadata.capabilityId.ifBlank { existing?.capabilityId.orEmpty() },
                title = metadata.title.ifBlank { existing?.title.orEmpty() },
                input = metadata.input.ifBlank { existing?.input.orEmpty() },
                idempotencyKey = idempotencyKey ?: existing?.idempotencyKey,
                maxRetries = maxRetries,
                availableAt = now,
                finishedAt = 0L,
                updatedAt = now
            )
            if (existing != null) {
                states.put(next)
                next to true
            } else {
                val canonical = states.putIfAbsent(next)
                if (canonical.taskId != next.taskId) canonical to false else next to true
            }
        }
        if (shouldQueue) {
            queue.send(queued.taskId)
            trace(queued.taskId, "queued", owner)
        }
        return queued
    }

    /**
     * Registers a task whose durable wake-up is owned by a platform scheduler such as WorkManager.
     * Registration persists the canonical task state but deliberately does not dispatch it in-process.
     */
    suspend fun registerExternallyScheduled(state: AgentTaskState): AgentTaskState {
        val canonical = withTransitionLock {
            val existing = states.get(state.taskId)
            if (existing != null) {
                require(existing.owner == state.owner) { "任务 ID 已被其他模块占用" }
                existing
            } else {
                states.putIfAbsent(state)
            }
        }
        return canonical
    }

    /**
     * Moves an externally scheduled task into the shared queue without starting execution.
     * The platform Worker will call dispatchExternallyScheduled when its durable request runs.
     */
    suspend fun prepareExternallyScheduled(
        taskId: String,
        owner: String,
        checkpoint: String? = null,
        metadata: AgentTaskMetadata = AgentTaskMetadata()
    ): AgentTaskState? {
        val (prepared, changed) = withTransitionLock {
            val existing = states.get(taskId) ?: return@withTransitionLock null to false
            require(existing.owner == owner) { "任务 ID 已被其他模块占用" }
            if (existing.status !in setOf(
                    AgentTaskStatus.CREATED,
                    AgentTaskStatus.PAUSED,
                    AgentTaskStatus.FAILED,
                    AgentTaskStatus.WAITING_FOR_USER,
                    AgentTaskStatus.RETRY_WAIT
                )) return@withTransitionLock existing to false
            val next = existing.copy(
                status = AgentTaskStatus.QUEUED,
                checkpoint = checkpoint?.take(64_000) ?: existing.checkpoint,
                lastError = "",
                projectId = metadata.projectId.ifBlank { existing.projectId },
                conversationId = metadata.conversationId.ifBlank { existing.conversationId },
                kind = metadata.kind.ifBlank { existing.kind },
                capabilityId = metadata.capabilityId.ifBlank { existing.capabilityId },
                title = metadata.title.ifBlank { existing.title },
                input = metadata.input.ifBlank { existing.input },
                availableAt = System.currentTimeMillis(),
                finishedAt = 0L,
                updatedAt = System.currentTimeMillis()
            )
            states.put(next)
            next to true
        }
        if (changed && prepared != null) trace(taskId, "externally_queued", owner)
        return prepared
    }

    /** Dispatches a previously persisted task only when its WorkManager request starts. */
    suspend fun dispatchExternallyScheduled(taskId: String, owner: String): AgentTaskState? {
        val state = prepareExternallyScheduled(taskId, owner) ?: return null
        if (state.status == AgentTaskStatus.QUEUED && !activeJobs.containsKey(taskId)) {
            queue.send(taskId)
            trace(taskId, "external_scheduler_dispatched", owner)
        }
        return state
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

    /**
     * Pre-registers a task that will be executed by a feature's runInline handler after
     * its durable WorkManager request wakes the process. The owner identity matches runInline.
     */
    suspend fun registerInlineTask(
        taskId: String,
        owner: String,
        checkpoint: String = "",
        metadata: AgentTaskMetadata = AgentTaskMetadata()
    ): AgentTaskState {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        val state = AgentTaskState(
            taskId = taskId,
            owner = inlineHandlerId(owner, taskId),
            status = AgentTaskStatus.QUEUED,
            checkpoint = checkpoint.take(64_000),
            projectId = metadata.projectId,
            conversationId = metadata.conversationId,
            kind = metadata.kind,
            capabilityId = metadata.capabilityId,
            title = metadata.title,
            input = metadata.input
        )
        return registerExternallyScheduled(state)
    }

    private fun inlineHandlerId(owner: String, taskId: String): String =
        owner.take(32) + "-" + taskId.hashCode().toUInt().toString(16)

    suspend fun <T> runInline(
        taskId: String,
        owner: String,
        initialCheckpoint: String = "",
        metadata: AgentTaskMetadata = AgentTaskMetadata(),
        idempotencyKey: String? = null,
        maxRetries: Int = 2,
        handler: suspend (AgentTaskExecutionContext) -> T
    ): T {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        // Stable task-scoped handler identity allows the owning feature to re-register
        // its handler and resume a durable checkpoint after process recreation.
        val handlerId = inlineHandlerId(owner, taskId)
        val existing = withTransitionLock {
            val current = states.get(taskId)
            if (current == null) {
                null
            } else {
                require(current.owner == handlerId) { "runInline 任务 ID 已被其他任务占用" }
                if (current.status == AgentTaskStatus.RUNNING && !activeJobs.containsKey(taskId)) {
                    current.copy(
                        status = AgentTaskStatus.PAUSED,
                        lastError = "外部 Worker 已重新唤醒任务，正在从持久检查点恢复",
                        updatedAt = System.currentTimeMillis()
                    ).also(states::put)
                } else current
            }
        }
        if (existing != null) {
            require(existing.status !in setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) {
                "任务当前状态为 ${existing.status}，不能重复启动"
            }
        }
        val completion = CompletableDeferred<T>()
        handlers.register(handlerId) { context ->
            try {
                completion.complete(handler(context))
            } catch (error: Throwable) {
                val latest = states.get(taskId)
                val willRetry = AgentTaskRetryPolicy.isRetryable(error) &&
                    latest != null && latest.attempts < latest.maxRetries
                if (!willRetry) completion.completeExceptionally(error)
                throw error
            }
        }
        try {
            val queued = enqueue(
                taskId,
                handlerId,
                if (existing == null) initialCheckpoint else null,
                metadata = metadata,
                idempotencyKey = if (existing == null) idempotencyKey else null,
                maxRetries = maxRetries
            )
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

    /** Persist a lifecycle transition for a task coordinated by this shared task machine. */
    suspend fun transitionTask(
        taskId: String,
        status: AgentTaskStatus,
        reason: String = "",
        resultSummary: String? = null
    ): AgentTaskState? {
        val existing = states.get(taskId) ?: return null
        if (existing.status in setOf(AgentTaskStatus.COMPLETED, AgentTaskStatus.CANCELLED)) return existing
        AgentTaskExecutionContext(taskId, states, traces).transition(
            status = status,
            reason = reason,
            resultSummary = resultSummary
        )
        return states.get(taskId)
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
                    AgentTaskStatus.RETRY_WAIT -> {
                        val waitMillis = (latest.availableAt - System.currentTimeMillis()).coerceAtLeast(0L)
                        if (latest.owner in externallyScheduledOwners) {
                            latest
                        } else if (handlers.find(latest.owner) == null) {
                            val paused = latest.copy(
                                status = AgentTaskStatus.PAUSED,
                                lastError = "任务处理器尚未恢复；请重新打开对应功能后再继续",
                                updatedAt = System.currentTimeMillis()
                            )
                            states.put(paused)
                            paused
                        } else if (waitMillis == 0L) {
                            val queued = latest.copy(status = AgentTaskStatus.QUEUED, updatedAt = System.currentTimeMillis())
                            states.put(queued)
                            queuedIds += latest.taskId
                            queued
                        } else {
                            scheduleRetry(latest.taskId, waitMillis)
                            latest
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

    private fun scheduleRetry(taskId: String, delayMillis: Long) {
        scope.launch {
            delay(delayMillis.coerceAtLeast(0L))
            val shouldQueue = withTransitionLock {
                val latest = states.get(taskId) ?: return@withTransitionLock false
                if (latest.status != AgentTaskStatus.RETRY_WAIT || latest.availableAt > System.currentTimeMillis()) {
                    return@withTransitionLock false
                }
                states.put(latest.copy(status = AgentTaskStatus.QUEUED, lastError = "", updatedAt = System.currentTimeMillis()))
                true
            }
            if (shouldQueue) {
                queue.send(taskId)
                trace(taskId, "retry_ready", "退避结束，任务重新进入队列")
            }
        }
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
                        startedAt = if (latest.startedAt == 0L) System.currentTimeMillis() else latest.startedAt,
                        finishedAt = 0L,
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
                            finishedAt = System.currentTimeMillis(),
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
                var retryDelayMillis = 0L
                withContext(NonCancellable) {
                    val latest = states.get(taskId) ?: initial
                    val message = error.message?.take(2_000) ?: "任务执行失败"
                    if (AgentTaskRetryPolicy.isRetryable(error) && latest.attempts < latest.maxRetries) {
                        val nextAttempt = latest.attempts + 1
                        retryDelayMillis = AgentTaskRetryPolicy.backoffMillis(nextAttempt)
                        val now = System.currentTimeMillis()
                        states.put(latest.copy(
                            status = AgentTaskStatus.RETRY_WAIT,
                            attempts = nextAttempt,
                            availableAt = now + retryDelayMillis,
                            lastError = message,
                            finishedAt = 0L,
                            updatedAt = now
                        ))
                        trace(taskId, "retry_scheduled", "第 " + nextAttempt + " 次重试将在 " + retryDelayMillis + "ms 后执行：" + message)
                    } else {
                        states.put(latest.copy(
                            status = AgentTaskStatus.FAILED,
                            lastError = message,
                            finishedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                        trace(taskId, "failed", message)
                    }
                }
                if (retryDelayMillis > 0L) scheduleRetry(taskId, retryDelayMillis)
            } finally {
                AgentToolLeaseRegistry.revokeTask(taskId)
                AgentCredentialLeaseRegistry.revokeTask(taskId)
                withTransitionLock { activeJobs.remove(taskId, job) }
            }
        }
    }
}
