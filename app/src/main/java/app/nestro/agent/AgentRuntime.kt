package app.nestro.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared execution boundary for all Nestro agent surfaces.
 *
 * Planning remains owned by the calling feature (experts, workflows, Phone Agent).
 * This kernel owns tool discovery, authorization, execution state and checkpoints.
 */
enum class AgentTaskStatus {
    CREATED, QUEUED, RUNNING, WAITING_FOR_USER, PAUSED, COMPLETED, FAILED, CANCELLED
}

enum class AgentToolPermission {
    READ_ONLY,
    DEVICE_ACTION,
    EXTERNAL_WRITE,
    AUTHENTICATION,
    FINANCIAL
}

data class AgentTaskState(
    val taskId: String,
    val owner: String,
    val status: AgentTaskStatus = AgentTaskStatus.CREATED,
    val currentStep: Int = 0,
    val checkpoint: String = "",
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(owner.isNotBlank() && owner.length <= 80)
        require(currentStep >= 0)
        require(checkpoint.length <= 64_000)
        require(lastError.length <= 2_000)
    }
}

data class AgentToolSpec(
    val id: String,
    val description: String,
    val permission: AgentToolPermission,
    val requiresConfirmation: Boolean = false
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_.-]{1,100}")))
        require(description.isNotBlank() && description.length <= 500)
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

interface AgentToolCatalog {
    fun register(spec: AgentToolSpec, handler: suspend (Map<String, String>) -> AgentToolResult)
    fun remove(toolId: String)
    fun find(toolId: String): AgentToolSpec?
    fun list(): List<AgentToolSpec>
    suspend fun invoke(toolId: String, arguments: Map<String, String>): AgentToolResult
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
    suspend fun put(state: AgentTaskState)
    suspend fun recoverable(): List<AgentTaskState>
}

class InMemoryAgentTaskStateStore : AgentTaskStateStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, AgentTaskState>()

    override suspend fun get(taskId: String): AgentTaskState? = mutex.withLock { states[taskId] }

    override suspend fun put(state: AgentTaskState) {
        mutex.withLock { states[state.taskId] = state }
    }

    override suspend fun recoverable(): List<AgentTaskState> = mutex.withLock {
        states.values.filter { it.status in setOf(
            AgentTaskStatus.QUEUED,
            AgentTaskStatus.RUNNING,
            AgentTaskStatus.WAITING_FOR_USER,
            AgentTaskStatus.PAUSED,
            AgentTaskStatus.FAILED
        ) }
    }
}

interface AgentRuntime {
    suspend fun execute(request: AgentToolRequest): AgentToolResult
}

class DefaultAgentRuntime(
    private val catalog: AgentToolCatalog,
    private val permissions: AgentPermissionRouter,
    private val states: AgentTaskStateStore,
    private val traces: AgentTaskTraceStore? = null
) : AgentRuntime {
    private suspend fun trace(taskId: String, event: String, detail: String = "") {
        runCatching { traces?.append(AgentTaskTrace(taskId = taskId, event = event, detail = detail.take(2_000))) }
    }
    override suspend fun execute(request: AgentToolRequest): AgentToolResult {
        val current = states.get(request.taskId) ?: AgentTaskState(
            taskId = request.taskId,
            owner = "unspecified"
        )
        val spec = catalog.find(request.toolId) ?: return AgentToolResult(
            success = false,
            error = "工具不存在或尚未注册：${request.toolId}"
        )
        trace(request.taskId, "tool_requested", request.toolId)
        val authorization = permissions.authorize(current, spec)
        when (authorization) {
            is AgentAuthorization.Deny -> {
                trace(request.taskId, "tool_denied", authorization.reason)
                states.put(current.copy(
                    status = AgentTaskStatus.FAILED,
                    lastError = authorization.reason,
                    updatedAt = System.currentTimeMillis()
                ))
                return AgentToolResult(false, error = authorization.reason)
            }
            is AgentAuthorization.RequireConfirmation -> {
                trace(request.taskId, "waiting_for_user", authorization.reason)
                states.put(current.copy(
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

        states.put(current.copy(
            status = AgentTaskStatus.RUNNING,
            lastError = "",
            updatedAt = System.currentTimeMillis()
        ))
        return try {
            val result = catalog.invoke(request.toolId, request.arguments)
            trace(request.taskId, if (result.success) "tool_succeeded" else "tool_failed", result.error.ifBlank { result.output })
            val latest = states.get(request.taskId) ?: current
            states.put(latest.copy(
                status = if (result.success) AgentTaskStatus.RUNNING else AgentTaskStatus.FAILED,
                currentStep = latest.currentStep + if (result.success) 1 else 0,
                lastError = if (result.success) "" else result.error.ifBlank { result.output }.take(2_000),
                updatedAt = System.currentTimeMillis()
            ))
            result
        } catch (cancelled: CancellationException) {
            val latest = states.get(request.taskId) ?: current
            trace(request.taskId, "tool_cancelled", "工具执行被取消")
            states.put(latest.copy(
                status = AgentTaskStatus.PAUSED,
                lastError = "任务已取消，可从最近检查点恢复",
                updatedAt = System.currentTimeMillis()
            ))
            throw cancelled
        } catch (error: Exception) {
            val latest = states.get(request.taskId) ?: current
            val message = error.message?.take(2_000) ?: "工具执行失败"
            trace(request.taskId, "tool_failed", message)
            states.put(latest.copy(
                status = AgentTaskStatus.FAILED,
                lastError = message,
                updatedAt = System.currentTimeMillis()
            ))
            AgentToolResult(success = false, error = message)
        }
    }
}
