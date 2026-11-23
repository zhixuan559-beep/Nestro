package app.nestro.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Shared execution boundary for all Nestro agent surfaces.
 *
 * Planning remains owned by the calling feature (experts, workflows, Phone Agent).
 * This kernel owns tool discovery, authorization, execution state and checkpoints.
 */
enum class AgentTaskStatus {
    CREATED, QUEUED, RUNNING, WAITING_FOR_USER, WAITING_FOR_TASK, PAUSED, RETRY_WAIT, COMPLETED, FAILED, CANCELLED
}

enum class AgentToolPermission {
    READ_ONLY,
    DEVICE_ACTION,
    EXTERNAL_WRITE,
    AUTHENTICATION,
    FINANCIAL
}

data class AgentTaskMetadata(
    val projectId: String = "",
    val conversationId: String = "",
    val kind: String = "",
    val capabilityId: String = "",
    val title: String = "",
    val input: String = ""
)

class RetryableAgentTaskException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NonRetryableAgentTaskException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object AgentTaskRetryPolicy {
    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode in setOf(408, 425, 429) || statusCode in 500..599

    fun exceptionForHttpStatus(statusCode: Int, message: String): Exception =
        if (isRetryableHttpStatus(statusCode)) RetryableAgentTaskException(message)
        else IllegalStateException(message)

    fun isRetryable(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it is NonRetryableAgentTaskException }) return false
        return causes.any {
        it is RetryableAgentTaskException ||
            it is java.net.SocketTimeoutException ||
            it is java.net.ConnectException ||
            it is java.net.UnknownHostException ||
            it is java.net.SocketException
        }
    }

    fun backoffMillis(attempt: Int): Long {
        require(attempt >= 1)
        return (250L shl (attempt - 1).coerceAtMost(7)).coerceAtMost(30_000L)
    }
}

data class AgentTaskState(
    val taskId: String,
    val owner: String,
    val status: AgentTaskStatus = AgentTaskStatus.CREATED,
    val currentStep: Int = 0,
    val checkpoint: String = "",
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val projectId: String = "",
    val conversationId: String = "",
    val kind: String = "",
    val capabilityId: String = "",
    val title: String = "",
    val input: String = "",
    val resultSummary: String = "",
    val idempotencyKey: String? = null,
    val attempts: Int = 0,
    val maxRetries: Int = 2,
    val availableAt: Long = 0L,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L
) {
    init {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        require(currentStep >= 0 && attempts >= 0 && maxRetries in 0..5)
        require(checkpoint.length <= 64_000 && lastError.length <= 2_000)
        require(projectId.length <= 120 && conversationId.length <= 120)
        require(kind.length <= 60 && capabilityId.length <= 100)
        require(title.length <= 160 && input.length <= 48_000 && resultSummary.length <= 8_000)
        require(idempotencyKey == null || idempotencyKey.length in 1..240)
        require(availableAt >= 0L && startedAt >= 0L && finishedAt >= 0L)
    }
}

data class AgentToolSpec(
    val id: String,
    val description: String,
    val permission: AgentToolPermission,
    val requiresConfirmation: Boolean = false,
    val parallelSafe: Boolean = false
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_.-]{1,100}")))
        require(description.isNotBlank() && description.length <= 500)
        require(!parallelSafe || (permission == AgentToolPermission.READ_ONLY && !requiresConfirmation)) {
            "只有明确声明且无需审批的只读工具可以并发"
        }
    }
}

data class AgentToolRequest(
    val taskId: String,
    val toolId: String,
    val arguments: Map<String, String> = emptyMap()
)

data class AgentToolResult(
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val requiresUser: Boolean = false
)

sealed interface AgentAuthorization {
    data object Allow : AgentAuthorization
    data class Deny(val reason: String) : AgentAuthorization
    data class RequireConfirmation(val reason: String) : AgentAuthorization
}

interface AgentToolDiscovery {
    fun find(toolId: String): AgentToolSpec?
    fun list(): List<AgentToolSpec>
}

/** Invocation is reserved for AgentRuntime, which applies AgentPermissionRouter. */
interface AgentToolAccess : AgentToolDiscovery {
    suspend fun invoke(toolId: String, arguments: Map<String, String>): AgentToolResult
}

interface AgentToolCatalog : AgentToolAccess {
    fun register(spec: AgentToolSpec, handler: suspend (Map<String, String>) -> AgentToolResult)
    fun remove(toolId: String)

    /** Creates a task-local view that exposes only explicitly selected capabilities. */
    fun openSession(toolIds: Set<String>): AgentToolSession {
        val selected = toolIds.mapNotNull(::find).associateBy { it.id }
        return AgentToolSession(selected) { toolId, arguments -> invoke(toolId, arguments) }
    }
}

/**
 * A capability lease for one task/step. Tool definitions outside the allow-list
 * are invisible, invocation outside the allow-list is rejected, and close() makes
 * the lease unusable. Sessions never grant permissions; AgentPermissionRouter still
 * authorizes every invocation.
 */
class AgentToolSession internal constructor(
    allowedSpecs: Map<String, AgentToolSpec>,
    private val invokeSelected: suspend (String, Map<String, String>) -> AgentToolResult
) : AgentToolAccess, AutoCloseable {
    private val specs = allowedSpecs.toMap()
    @Volatile private var closed = false

    override fun find(toolId: String): AgentToolSpec? =
        if (closed) null else specs[toolId]

    override fun list(): List<AgentToolSpec> =
        if (closed) emptyList() else specs.values.sortedBy { it.id }

    override suspend fun invoke(toolId: String, arguments: Map<String, String>): AgentToolResult {
        if (closed) return AgentToolResult(false, error = "工具会话已释放")
        if (toolId !in specs) return AgentToolResult(false, error = "当前任务未加载该工具：$toolId")
        return invokeSelected(toolId, arguments)
    }

    override fun close() {
        closed = true
    }

    /** Revocation is explicit and immediate; it has the same terminal effect as close(). */
    fun revoke() {
        closed = true
    }
}

/**
 * Process-local registry for task-scoped capability leases.
 * A task can revoke every open lease without holding individual session references.
 */
object AgentToolLeaseRegistry {
    data class Lease internal constructor(
        val id: String,
        val taskId: String,
        val access: AgentToolSession
    )

    private val leases = ConcurrentHashMap<String, Lease>()

    fun open(taskId: String, catalog: AgentToolCatalog, toolIds: Set<String>): Lease {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(toolIds.isNotEmpty())
        val lease = Lease(
            id = java.util.UUID.randomUUID().toString(),
            taskId = taskId,
            access = catalog.openSession(toolIds)
        )
        leases[lease.id] = lease
        return lease
    }

    fun release(leaseId: String): Boolean {
        val lease = leases.remove(leaseId) ?: return false
        lease.access.close()
        return true
    }

    fun revoke(leaseId: String): Boolean {
        val lease = leases.remove(leaseId) ?: return false
        lease.access.revoke()
        return true
    }

    fun revokeTask(taskId: String): Int {
        val selected = leases.values.filter { it.taskId == taskId }
        selected.forEach { lease ->
            if (leases.remove(lease.id, lease)) lease.access.revoke()
        }
        return selected.size
    }

    fun activeLeaseCount(taskId: String): Int = leases.values.count { it.taskId == taskId }
}

/**
 * A short-lived credential view for one task. The secret is resolved only when
 * used, never copied into the lease registry or persisted by this abstraction.
 */
class AgentCredentialLease internal constructor(
    val id: String,
    val taskId: String,
    private val expiresAtMillis: Long,
    private val secretProvider: () -> String?
) : AutoCloseable {
    @Volatile private var revoked = false

    fun isUsable(nowMillis: Long = System.currentTimeMillis()): Boolean =
        !revoked && nowMillis < expiresAtMillis

    fun <T> withSecret(block: (String) -> T): T? {
        if (!isUsable()) return null
        val secret = secretProvider()?.takeIf { it.isNotBlank() } ?: return null
        if (!isUsable()) return null
        return block(secret)
    }

    fun revoke() {
        revoked = true
    }

    override fun close() = revoke()
}

/** In-memory, task-bound credential leases; credentials themselves remain in their secure owner. */
object AgentCredentialLeaseRegistry {
    private val leases = ConcurrentHashMap<String, AgentCredentialLease>()

    fun open(
        taskId: String,
        ttlMillis: Long = 60_000,
        secretProvider: () -> String?
    ): AgentCredentialLease {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(ttlMillis in 1..900_000) { "凭据租约有效期必须为 1 毫秒到 15 分钟" }
        val lease = AgentCredentialLease(
            id = java.util.UUID.randomUUID().toString(),
            taskId = taskId,
            expiresAtMillis = System.currentTimeMillis() + ttlMillis,
            secretProvider = secretProvider
        )
        leases[lease.id] = lease
        return lease
    }

    fun release(leaseId: String): Boolean {
        val lease = leases.remove(leaseId) ?: return false
        lease.close()
        return true
    }

    fun revokeTask(taskId: String): Int {
        val selected = leases.values.filter { it.taskId == taskId }
        selected.forEach { lease ->
            if (leases.remove(lease.id, lease)) lease.revoke()
        }
        return selected.size
    }

    fun activeLeaseCount(taskId: String): Int =
        leases.values.count { it.taskId == taskId && it.isUsable() }
}

/**
 * Central declaration point for the minimum tools required by one feature operation.
 * Declarations are intentionally operation-scoped (expert member / workflow step),
 * not a global list injected into every task or chat turn.
 */
object AgentCapabilityRegistry {
    private data class Key(val owner: String, val operation: String)
    private val declarations = ConcurrentHashMap<Key, Set<String>>()

    fun register(owner: String, operation: String, toolIds: Set<String>) {
        require(owner.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        require(operation.isNotBlank() && operation.length <= 120)
        require(toolIds.size <= 32)
        require(toolIds.all { it.matches(Regex("[a-zA-Z0-9_.-]{1,100}")) })
        declarations[Key(owner, operation)] = toolIds.toSortedSet()
    }

    fun unregister(owner: String, operation: String): Boolean =
        declarations.remove(Key(owner, operation)) != null

    fun resolve(owner: String, operation: String): Set<String> =
        declarations[Key(owner, operation)].orEmpty().toSet()

    fun clearOwner(owner: String) {
        declarations.keys.removeIf { it.owner == owner }
    }

    fun openLease(
        taskId: String,
        owner: String,
        operation: String,
        catalog: AgentToolCatalog?
    ): AgentToolLeaseRegistry.Lease? {
        val required = resolve(owner, operation)
        if (required.isEmpty()) return null
        requireNotNull(catalog) {
            "操作 $owner/$operation 声明了工具能力，但当前没有可用的统一工具目录"
        }
        val available = catalog.list().map { it.id }.toSet()
        val missing = required - available
        require(missing.isEmpty()) {
            "操作 $owner/$operation 所需工具尚未注册：" + missing.sorted().joinToString("、")
        }
        return AgentToolLeaseRegistry.open(taskId, catalog, required)
    }
}

class InMemoryAgentToolCatalog : AgentToolCatalog {
    private data class Entry(
        val spec: AgentToolSpec,
        val handler: suspend (Map<String, String>) -> AgentToolResult
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    override fun register(
        spec: AgentToolSpec,
        handler: suspend (Map<String, String>) -> AgentToolResult
    ) {
        entries[spec.id] = Entry(spec, handler)
    }

    override fun remove(toolId: String) {
        entries.remove(toolId)
    }

    override fun find(toolId: String): AgentToolSpec? = entries[toolId]?.spec

    override fun list(): List<AgentToolSpec> = entries.values
        .map { it.spec }
        .sortedBy { it.id }

    /** Captures both definitions and handlers so later registry changes cannot retarget an active lease. */
    override fun openSession(toolIds: Set<String>): AgentToolSession {
        val selected = toolIds.mapNotNull { toolId ->
            entries[toolId]?.let { toolId to it }
        }.toMap()
        return AgentToolSession(selected.mapValues { it.value.spec }) { toolId, arguments ->
            selected[toolId]?.handler?.invoke(arguments)
                ?: AgentToolResult(false, error = "当前任务的工具能力已失效：$toolId")
        }
    }

    override suspend fun invoke(
        toolId: String,
        arguments: Map<String, String>
    ): AgentToolResult {
        val entry = entries[toolId] ?: return AgentToolResult(
            success = false,
            error = "工具不存在或尚未注册：$toolId"
        )
        return entry.handler(arguments)
    }
}


enum class AgentApprovalDecision { AUTHORIZE_ONCE, AUTHORIZE_TASK, REJECT }

data class AgentApprovalRequest(
    val id: String,
    val scopeId: String,
    val toolId: String,
    val reason: String
)

/**
 * One approval boundary shared by chat tools, generated plugins, MCP and workflows.
 *
 * Durable approval continuation is adapted from NoHarry/android-agent-runtime's
 * ThreadApproval and ThreadApprovalContinuation patterns (MIT; see the third-party license).
 * Decisions are persisted before waking an active caller, so interrupted tasks can
 * consume the same decision after process recovery.
 */
class AgentApprovalPendingException(message: String) : CancellationException(message)

data class AgentApprovalAuditRecord(
    val id: String,
    val scopeId: String,
    val toolId: String,
    val reason: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

class AgentApprovalCoordinator(
    private val persistAudit: suspend (AgentApprovalAuditRecord) -> Unit = {},
    private val closeActiveAudits: suspend (scopeId: String, status: String, updatedAt: Long) -> Unit = { _, _, _ -> },
    private val loadPendingAudits: suspend () -> List<AgentApprovalAuditRecord> = { emptyList() },
    private val loadAudit: suspend (id: String) -> AgentApprovalAuditRecord? = { null },
    private val loadAuditsForScope: suspend (scopeId: String) -> List<AgentApprovalAuditRecord> = { emptyList() },
    private val loadResumableAudits: suspend () -> List<AgentApprovalAuditRecord> = { emptyList() },
    private val resumeTask: suspend (scopeId: String) -> Unit = {}
) {
    private data class ActiveApproval(
        val record: AgentApprovalAuditRecord,
        val decision: CompletableDeferred<AgentApprovalDecision>
    )

    private sealed class ApprovalRegistration {
        data class Resolved(val decision: AgentApprovalDecision) : ApprovalRegistration()
        data class Pending(
            val record: AgentApprovalAuditRecord,
            val deferred: CompletableDeferred<AgentApprovalDecision>
        ) : ApprovalRegistration()
    }

    private val taskWideGrants = ConcurrentHashMap.newKeySet<String>()
    private val requestLocks = ConcurrentHashMap<String, Mutex>()
    private val approvalScopeLocks = ConcurrentHashMap<String, Mutex>()
    private val promptMutex = Mutex()
    private val activeRequests = ConcurrentHashMap<String, ActiveApproval>()
    @Volatile private var handler: (suspend (AgentApprovalRequest) -> Unit)? = null

    fun setHandler(value: (suspend (AgentApprovalRequest) -> Unit)?) {
        handler = value
    }

    suspend fun pendingRequests(): List<AgentApprovalRequest> =
        loadPendingAudits().map { it.toRequest() }

    /**
     * Resolves an approval either in the live coroutine or, after process death,
     * in the durable audit store and resumes the owning task.
     */
    suspend fun respond(
        approvalId: String,
        decision: AgentApprovalDecision,
        resumeTaskIfInactive: Boolean = true
    ): Boolean {
        val knownRecord = activeRequests[approvalId]?.record ?: loadAudit(approvalId) ?: return false
        val approvalScopeLock = approvalScopeLocks.computeIfAbsent(knownRecord.scopeId) { Mutex() }
        var resumeScope: String? = null
        val accepted = approvalScopeLock.withLock {
            val active = activeRequests[approvalId]
            if (active != null) {
                if (!active.decision.isActive) return@withLock false
                persistAudit(active.record.copy(status = decisionStatus(decision), updatedAt = System.currentTimeMillis()))
                return@withLock active.decision.complete(decision)
            }

            val record = loadAudit(approvalId) ?: return@withLock false
            if (record.status != "PENDING") return@withLock false
            persistAudit(record.copy(status = decisionStatus(decision), updatedAt = System.currentTimeMillis()))
            if (resumeTaskIfInactive && requestLocks[record.scopeId]?.isLocked != true) {
                resumeScope = record.scopeId
            }
            true
        }
        if (accepted && resumeScope != null) {
            runCatching { resumeTask(resumeScope!!) }
        }
        return accepted
    }

    /** Reattach decisions committed just before a process interruption. */
    suspend fun resumeResolvedTasks() {
        loadResumableAudits()
            .distinctBy { it.scopeId }
            .forEach { record ->
                runCatching { resumeTask(record.scopeId) }
            }
    }

    suspend fun authorize(scopeId: String, toolId: String, reason: String): AgentApprovalDecision? {
        require(scopeId.isNotBlank() && scopeId.length <= 160)
        require(toolId.isNotBlank() && toolId.length <= 100)
        if (scopeId in taskWideGrants) return AgentApprovalDecision.AUTHORIZE_TASK

        val lock = requestLocks.computeIfAbsent(scopeId) { Mutex() }
        return lock.withLock scopeLock@{
            if (scopeId in taskWideGrants) return@scopeLock AgentApprovalDecision.AUTHORIZE_TASK

            val approvalScopeLock = approvalScopeLocks.computeIfAbsent(scopeId) { Mutex() }
            val registration = approvalScopeLock.withLock {
                val scopeAudits = loadAuditsForScope(scopeId).sortedBy { it.createdAt }
                if (scopeAudits.any { it.status == "AUTHORIZED_TASK" }) {
                    taskWideGrants += scopeId
                    return@withLock ApprovalRegistration.Resolved(AgentApprovalDecision.AUTHORIZE_TASK)
                }

                val priorDecision = scopeAudits.firstOrNull {
                    it.toolId == toolId && it.status in setOf("AUTHORIZED_ONCE", "REJECTED")
                }
                if (priorDecision != null) {
                    val decision = if (priorDecision.status == "AUTHORIZED_ONCE") {
                        AgentApprovalDecision.AUTHORIZE_ONCE
                    } else {
                        AgentApprovalDecision.REJECT
                    }
                    val consumedStatus = if (decision == AgentApprovalDecision.AUTHORIZE_ONCE) {
                        "CONSUMED_ONCE"
                    } else {
                        "CONSUMED_REJECTED"
                    }
                    persistAudit(priorDecision.copy(status = consumedStatus, updatedAt = System.currentTimeMillis()))
                    return@withLock ApprovalRegistration.Resolved(decision)
                }

                val pending = scopeAudits.firstOrNull { it.toolId == toolId && it.status == "PENDING" }
                val now = System.currentTimeMillis()
                val record = pending ?: AgentApprovalAuditRecord(
                    id = UUID.randomUUID().toString(),
                    scopeId = scopeId,
                    toolId = toolId,
                    reason = reason.take(2_000),
                    status = "PENDING",
                    createdAt = now,
                    updatedAt = now
                )
                val deferred = CompletableDeferred<AgentApprovalDecision>()
                if (pending == null) persistAudit(record)
                activeRequests[record.id] = ActiveApproval(record, deferred)
                ApprovalRegistration.Pending(record, deferred)
            }

            when (registration) {
                is ApprovalRegistration.Resolved -> registration.decision
                ApprovalRegistration.Unavailable -> null
                is ApprovalRegistration.Pending -> {
                    val record = registration.record
                    val deferred = registration.deferred
                    try {
                        val approvalHandler = handler
                        if (approvalHandler == null && !deferred.isCompleted) return@scopeLock null
                        if (approvalHandler != null && !deferred.isCompleted) {
                            // Serialize only the UI enqueue operation, never the user's wait.
                            // Other tasks can publish approvals and receive decisions concurrently.
                            promptMutex.withLock {
                                if (!deferred.isCompleted) approvalHandler(record.toRequest())
                            }
                        }
                        consumeDecision(record, deferred.await())
                    } finally {
                        activeRequests.remove(record.id)
                    }
                }
            }
        }
    }

    private suspend fun consumeDecision(
        record: AgentApprovalAuditRecord,
        decision: AgentApprovalDecision
    ): AgentApprovalDecision {
        when (decision) {
            AgentApprovalDecision.AUTHORIZE_ONCE ->
                persistAudit(record.copy(status = "CONSUMED_ONCE", updatedAt = System.currentTimeMillis()))
            AgentApprovalDecision.AUTHORIZE_TASK -> {
                taskWideGrants += record.scopeId
                persistAudit(record.copy(status = "AUTHORIZED_TASK", updatedAt = System.currentTimeMillis()))
            }
            AgentApprovalDecision.REJECT ->
                persistAudit(record.copy(status = "CONSUMED_REJECTED", updatedAt = System.currentTimeMillis()))
        }
        return decision
    }

    fun endScope(scopeId: String) {
        taskWideGrants.remove(scopeId)
        requestLocks.remove(scopeId)
    }

    suspend fun endScopeAndAudit(scopeId: String, status: String = "CLOSED") {
        require(status in setOf("CLOSED", "CANCELLED", "FAILED", "COMPLETED"))
        endScope(scopeId)
        runCatching {
            closeActiveAudits(scopeId, status, System.currentTimeMillis())
        }
    }

    private fun AgentApprovalAuditRecord.toRequest() =
        AgentApprovalRequest(id = id, scopeId = scopeId, toolId = toolId, reason = reason)

    private fun decisionStatus(decision: AgentApprovalDecision): String = when (decision) {
        AgentApprovalDecision.AUTHORIZE_ONCE -> "AUTHORIZED_ONCE"
        AgentApprovalDecision.AUTHORIZE_TASK -> "AUTHORIZED_TASK"
        AgentApprovalDecision.REJECT -> "REJECTED"
    }
}

fun interface AgentPermissionRouter {
    suspend fun authorize(
        task: AgentTaskState,
        tool: AgentToolSpec
    ): AgentAuthorization
}

class DefaultAgentPermissionRouter(
    private val approvedTaskIds: Set<String> = emptySet()
) : AgentPermissionRouter {
    override suspend fun authorize(
        task: AgentTaskState,
        tool: AgentToolSpec
    ): AgentAuthorization {
        if (tool.permission == AgentToolPermission.READ_ONLY && !tool.requiresConfirmation) {
            return AgentAuthorization.Allow
        }
        if (task.taskId in approvedTaskIds && !tool.requiresConfirmation) {
            return AgentAuthorization.Allow
        }
        return AgentAuthorization.RequireConfirmation(
            when (tool.permission) {
                AgentToolPermission.DEVICE_ACTION -> "设备操作需要当前任务授权"
                AgentToolPermission.EXTERNAL_WRITE -> "外部写入操作需要用户确认"
                AgentToolPermission.AUTHENTICATION -> "登录、验证码或身份验证操作必须由用户接手"
                AgentToolPermission.FINANCIAL -> "支付及资金操作必须由用户亲自完成"
                AgentToolPermission.READ_ONLY -> "该只读工具被标记为需要额外确认"
            }
        )
    }
}

interface AgentTaskStateStore {
    suspend fun get(taskId: String): AgentTaskState?
    suspend fun getByIdempotencyKey(key: String): AgentTaskState?
    suspend fun put(state: AgentTaskState)
    suspend fun putIfAbsent(state: AgentTaskState): AgentTaskState
    suspend fun recoverable(): List<AgentTaskState>
}

class InMemoryAgentTaskStateStore : AgentTaskStateStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, AgentTaskState>()

    override suspend fun get(taskId: String): AgentTaskState? = mutex.withLock { states[taskId] }

    override suspend fun getByIdempotencyKey(key: String): AgentTaskState? =
        mutex.withLock { states.values.firstOrNull { it.idempotencyKey == key } }

    override suspend fun put(state: AgentTaskState) {
        mutex.withLock {
            state.idempotencyKey?.let { key ->
                require(states.values.none { it.taskId != state.taskId && it.idempotencyKey == key }) {
                    "幂等键已被其他任务占用"
                }
            }
            states[state.taskId] = state
        }
    }

    override suspend fun putIfAbsent(state: AgentTaskState): AgentTaskState = mutex.withLock {
        state.idempotencyKey?.let { key ->
            states.values.firstOrNull { it.idempotencyKey == key }?.let { return@withLock it }
        }
        states[state.taskId] ?: state.also { states[state.taskId] = it }
    }

    override suspend fun recoverable(): List<AgentTaskState> = mutex.withLock {
        states.values.filter { it.status in setOf(
            AgentTaskStatus.QUEUED,
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.WAITING_FOR_USER,
            AgentTaskStatus.PAUSED,
            AgentTaskStatus.RETRY_WAIT,
            AgentTaskStatus.FAILED
        ) }
    }
}

interface AgentRuntime {
    suspend fun execute(request: AgentToolRequest): AgentToolResult
}

class DefaultAgentRuntime(
    private val catalog: AgentToolAccess,
    private val permissions: AgentPermissionRouter,
    private val states: AgentTaskStateStore,
    private val traces: AgentTaskTraceStore? = null,
    private val executionContext: AgentTaskExecutionContext? = null
) : AgentRuntime {
    private suspend fun trace(taskId: String, event: String, detail: String = "") {
        try {
            traces?.append(AgentTaskTrace(taskId = taskId, event = event, detail = detail.take(2_000)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Trace persistence must not fail the tool call, but cancellation must remain observable.
        }
    }
    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val current = states.get(request.taskId) ?: AgentTaskState(
            taskId = request.taskId,
            owner = "unspecified"
        )
        val spec = catalog.find(request.toolId) ?: run {
            val reason = "工具不存在或尚未注册：${request.toolId}"
            trace(request.taskId, "tool_missing", request.toolId)
            if (executionContext != null) executionContext.fail(reason) else states.put(current.copy(
                status = AgentTaskStatus.FAILED,
                lastError = reason,
                updatedAt = System.currentTimeMillis()
            ))
            return AgentToolResult(success = false, error = reason)
        }
        trace(request.taskId, "tool_requested", request.toolId)
        val authorization = permissions.authorize(current, spec)
        when (authorization) {
            is AgentAuthorization.Deny -> {
                trace(request.taskId, "tool_denied", authorization.reason)
                if (executionContext != null) executionContext.fail(authorization.reason) else states.put(current.copy(
                    status = AgentTaskStatus.FAILED,
                    lastError = authorization.reason,
                    updatedAt = System.currentTimeMillis()
                ))
                return AgentToolResult(false, error = authorization.reason)
            }
            is AgentAuthorization.RequireConfirmation -> {
                trace(request.taskId, "waiting_for_user", authorization.reason)
                if (executionContext != null) executionContext.waitForUser(authorization.reason) else states.put(current.copy(
                    status = AgentTaskStatus.WAITING_FOR_USER,
                    lastError = authorization.reason,
                    updatedAt = System.currentTimeMillis()
                ))
                return AgentToolResult(
                    success = false,
                    error = authorization.reason,
                    requiresUser = true
                )
            }
            AgentAuthorization.Allow -> Unit
        }

        if (executionContext == null) states.put(current.copy(
            status = AgentTaskStatus.RUNNING,
            lastError = "",
            updatedAt = System.currentTimeMillis()
        ))
        return try {
            val result = catalog.invoke(request.toolId, request.arguments)
            trace(request.taskId, if (result.success) "tool_succeeded" else "tool_failed", result.error.ifBlank { result.output })
            val latest = states.get(request.taskId) ?: current
            if (executionContext != null) {
                if (result.success) executionContext.transition(AgentTaskStatus.RUNNING)
                else executionContext.fail(result.error.ifBlank { result.output }.take(2_000))
            } else states.put(latest.copy(
                status = if (result.success) AgentTaskStatus.RUNNING else AgentTaskStatus.FAILED,
                currentStep = latest.currentStep + if (result.success) 1 else 0,
                lastError = if (result.success) "" else result.error.ifBlank { result.output }.take(2_000),
                updatedAt = System.currentTimeMillis()
            ))
            result
        } catch (cancelled: CancellationException) {
            val latest = states.get(request.taskId) ?: current
            if (cancelled is AgentApprovalPendingException) {
                val reason = cancelled.message?.take(2_000) ?: "任务等待用户审批"
                trace(request.taskId, "waiting_for_user", reason)
                if (executionContext != null) executionContext.waitForUser(reason) else states.put(latest.copy(
                    status = AgentTaskStatus.WAITING_FOR_USER,
                    lastError = reason,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                trace(request.taskId, "tool_cancelled", "工具执行被取消")
                if (executionContext != null) executionContext.pause("任务已取消，可从最近检查点恢复") else states.put(latest.copy(
                    status = AgentTaskStatus.PAUSED,
                    lastError = "任务已取消，可从最近检查点恢复",
                    updatedAt = System.currentTimeMillis()
                ))
            }
            throw cancelled
        } catch (error: Exception) {
            val latest = states.get(request.taskId) ?: current
            val message = error.message?.take(2_000) ?: "工具执行失败"
            trace(request.taskId, "tool_failed", message)
            if (executionContext != null) executionContext.fail(message) else states.put(latest.copy(
                status = AgentTaskStatus.FAILED,
                lastError = message,
                updatedAt = System.currentTimeMillis()
            ))
            AgentToolResult(success = false, error = message)
        }
    }
}
