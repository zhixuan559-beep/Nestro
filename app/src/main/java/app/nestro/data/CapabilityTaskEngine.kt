package app.nestro.data

import java.security.MessageDigest
import java.util.UUID

object CapabilityTaskState {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val WAITING_USER = "waiting_user"
    const val RETRY_WAIT = "retry_wait"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    private val terminal = setOf(SUCCEEDED, FAILED, CANCELLED)
    fun isTerminal(state: String): Boolean = state in terminal

    fun canTransition(from: String, to: String): Boolean = when (from) {
        QUEUED -> to in setOf(RUNNING, PAUSED, CANCELLED, WAITING_USER)
        RUNNING -> to in setOf(PAUSED, WAITING_USER, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED)
        PAUSED -> to in setOf(QUEUED, CANCELLED)
        WAITING_USER -> to in setOf(QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED)
        RETRY_WAIT -> to in setOf(RUNNING, PAUSED, CANCELLED, FAILED)
        else -> false
    }
}

object CapabilityTaskStore {
    private const val MAX_INPUT = 12_000
    private const val MAX_CHECKPOINT = 24_000
    private const val MAX_RESULT = 8_000

    fun idempotencyKey(projectId: String, kind: String, callerKey: String): String {
        require(projectId.isNotBlank() && projectId.length <= 120)
        require(kind.matches(Regex("[a-zA-Z0-9_.-]{1,60}")))
        require(callerKey.isNotBlank() && callerKey.length <= 240)
        val source = "$projectId|$kind|$callerKey"
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun enqueue(
        dao: NestroDao,
        projectId: String,
        conversationId: String,
        kind: String,
        capabilityId: String,
        title: String,
        input: String,
        callerKey: String,
        requiresConfirmation: Boolean = false,
        maxRetries: Int = 2
    ): CapabilityTaskEntity {
        require(projectId.isNotBlank() && conversationId.isNotBlank())
        require(kind.matches(Regex("[a-zA-Z0-9_.-]{1,60}")))
        require(capabilityId.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        require(title.isNotBlank() && title.length <= 120)
        require(input.isNotBlank() && input.length <= MAX_INPUT)
        require(maxRetries in 0..5)
        val key = idempotencyKey(projectId, kind, callerKey)
        dao.capabilityTaskByIdempotencyKey(key)?.let { return it }
        val now = System.currentTimeMillis()
        val task = CapabilityTaskEntity(
            id = UUID.randomUUID().toString(), projectId = projectId, conversationId = conversationId,
            kind = kind, capabilityId = capabilityId, title = title, input = input,
            state = CapabilityTaskState.QUEUED, idempotencyKey = key, checkpoint = "",
            resultSummary = "", error = "", attempts = 0, maxRetries = maxRetries,
            requiresConfirmation = requiresConfirmation, availableAt = now,
            createdAt = now, updatedAt = now, startedAt = 0L, finishedAt = 0L
        )
        val inserted = dao.insertCapabilityTask(task)
        if (inserted == -1L) return dao.capabilityTaskByIdempotencyKey(key) ?: error("任务幂等冲突，请重试")
        appendEvent(dao, task.id, "", CapabilityTaskState.QUEUED, "enqueued", "任务已进入队列")
        return task
    }

    suspend fun claimNext(dao: NestroDao, projectId: String): CapabilityTaskEntity? {
        repeat(3) {
            val candidate = dao.nextCapabilityTask(projectId, System.currentTimeMillis()) ?: return null
            val now = System.currentTimeMillis()
            if (dao.claimCapabilityTask(candidate.id, now) == 1) {
                appendEvent(dao, candidate.id, candidate.state, CapabilityTaskState.RUNNING, "claimed", "任务已被执行器领取")
                return dao.capabilityTask(candidate.id)
            }
        }
        return null
    }

    suspend fun transition(
        dao: NestroDao,
        taskId: String,
        toState: String,
        event: String,
        detail: String = "",
        checkpoint: String? = null,
        resultSummary: String? = null,
        error: String? = null,
        retryDelayMillis: Long = 0L
    ): Boolean {
        val task = dao.capabilityTask(taskId) ?: return false
        require(CapabilityTaskState.canTransition(task.state, toState)) { "不允许的任务状态迁移：${task.state} → $toState" }
        require((checkpoint?.length ?: 0) <= MAX_CHECKPOINT)
        require((resultSummary?.length ?: 0) <= MAX_RESULT)
        require((error?.length ?: 0) <= 2_000)
        require(retryDelayMillis in 0..86_400_000L)
        if (toState == CapabilityTaskState.RETRY_WAIT) {
            require(task.attempts <= task.maxRetries) { "任务已达到最大重试次数" }
        }
        val now = System.currentTimeMillis()
        val finished = if (CapabilityTaskState.isTerminal(toState)) now else 0L
        val changed = dao.transitionCapabilityTask(
            taskId, task.state, toState, checkpoint ?: task.checkpoint,
            resultSummary ?: task.resultSummary, error ?: "", now + retryDelayMillis, now, finished
        )
        if (changed == 1) appendEvent(dao, taskId, task.state, toState, event, detail)
        return changed == 1
    }

    suspend fun cancel(dao: NestroDao, taskId: String): Boolean =
        transition(dao, taskId, CapabilityTaskState.CANCELLED, "cancelled", "用户取消任务")

    suspend fun pause(dao: NestroDao, taskId: String): Boolean =
        transition(dao, taskId, CapabilityTaskState.PAUSED, "paused", "任务已暂停")

    suspend fun resume(dao: NestroDao, taskId: String): Boolean =
        transition(dao, taskId, CapabilityTaskState.QUEUED, "resumed", "任务已恢复")

    suspend fun retry(dao: NestroDao, taskId: String, delayMillis: Long = 0L): Boolean =
        transition(dao, taskId, CapabilityTaskState.RETRY_WAIT, "retry_scheduled", "任务已安排重试", retryDelayMillis = delayMillis)


    suspend fun recoverInterrupted(dao: NestroDao) {
        dao.allCapabilityTasks().filter {
            it.state == CapabilityTaskState.RUNNING && !it.checkpoint.startsWith("phone_agent_task_id:")
        }.forEach { task ->
            transition(dao, task.id, CapabilityTaskState.WAITING_USER, "process_recovery",
                "应用进程中断，任务已保留并等待用户恢复", error = "上次执行因应用进程中断而停止")
        }
    }

    private suspend fun appendEvent(dao: NestroDao, taskId: String, from: String, to: String, event: String, detail: String) {
        dao.insertCapabilityTaskEvent(
            CapabilityTaskEventEntity(UUID.randomUUID().toString(), taskId, from, to, event.take(40), detail.take(240), System.currentTimeMillis())
        )
        dao.trimCapabilityTaskEvents(taskId, 200)
    }
}
