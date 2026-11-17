package app.nestro

import android.app.Application
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentTaskHandlerRegistry
import app.nestro.agent.RoomAgentTaskStateStore
import app.nestro.agent.RoomAgentTaskTraceStore
import app.nestro.agent.AgentTaskState
import app.nestro.agent.AgentTaskStatus
import app.nestro.phone.PhoneAgentAgent
import app.nestro.phone.PhoneAgentExecutor
import app.nestro.phone.PhoneAgentWorkScheduler
import app.nestro.phone.PhoneAgentTaskService
import app.nestro.data.NestroDatabase
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.ConversationPersistenceService
import app.nestro.data.ChatRequestRecoveryCoordinator
import app.nestro.data.ConversationStartupCoordinator
import app.nestro.data.UserMemoryRepository
import app.nestro.data.ConversationContextRepository
import app.nestro.data.ProjectSpaceRepository
import app.nestro.data.KnowledgeRepository
import app.nestro.data.LocalBackupRepository
import app.nestro.data.ExpertTeamExecutionCoordinator
import app.nestro.data.ChatRequestContextAssembler
import app.nestro.data.AutomaticMemoryExtractionCoordinator
import app.nestro.data.extractMemoryCandidates
import app.nestro.data.WorkflowExecutionCoordinator
import app.nestro.model.ModelConfigurationRepository
import app.nestro.model.ModelConnectionService
import app.nestro.model.ChatExecutionService
import app.nestro.model.ChatStreamingCoordinator
import app.nestro.model.ChatStreamingTransport
import app.nestro.model.SharedPreferencesChatPartialStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns process-wide agent infrastructure so task execution is not tied to a
 * Compose screen or Activity lifecycle.
 */
class NestroApplication : Application() {
    val database: NestroDatabase by lazy { NestroDatabase.get(this) }
    val conversationPersistenceService: ConversationPersistenceService by lazy {
        ConversationPersistenceService(database)
    }
    internal val chatRequestRecoveryCoordinator: ChatRequestRecoveryCoordinator by lazy {
        ChatRequestRecoveryCoordinator(conversationPersistenceService)
    }
    internal val conversationStartupCoordinator: ConversationStartupCoordinator by lazy {
        ConversationStartupCoordinator(conversationPersistenceService)
    }
    val userMemoryRepository: UserMemoryRepository by lazy { UserMemoryRepository(database) }
    val conversationContextRepository: ConversationContextRepository by lazy { ConversationContextRepository(database) }
    val projectSpaceRepository: ProjectSpaceRepository by lazy { ProjectSpaceRepository(database) }
    val knowledgeRepository: KnowledgeRepository by lazy { KnowledgeRepository(database) }
    val localBackupRepository: LocalBackupRepository by lazy { LocalBackupRepository(this, database) }
    val modelConfigurationRepository: ModelConfigurationRepository by lazy {
        ModelConfigurationRepository(this)
    }
    val modelConnectionService: ModelConnectionService by lazy { ModelConnectionService() }
    val chatExecutionService: ChatExecutionService by lazy { ChatExecutionService() }
    internal val chatStreamingCoordinator: ChatStreamingCoordinator by lazy {
        ChatStreamingCoordinator(
            transport = ChatStreamingTransport { configs, history, systemPrompt, temperature, maxTokens, onUsage, requestId, shouldStop, onChunk ->
                chatExecutionService.streamWithFallback(
                    configs, history, systemPrompt, temperature, maxTokens, onUsage, requestId, shouldStop, onChunk
                )
            },
            partialStateStore = SharedPreferencesChatPartialStateStore(
                getSharedPreferences("nestro_local", MODE_PRIVATE)
            )
        )
    }
    val expertTeamExecutionCoordinator: ExpertTeamExecutionCoordinator by lazy {
        ExpertTeamExecutionCoordinator(agentTaskCoordinator)
    }
    val workflowExecutionCoordinator: WorkflowExecutionCoordinator by lazy {
        WorkflowExecutionCoordinator(agentTaskCoordinator)
    }
    internal val automaticMemoryExtractionCoordinator: AutomaticMemoryExtractionCoordinator by lazy {
        AutomaticMemoryExtractionCoordinator(
            extractCandidates = { configs, messages -> extractMemoryCandidates(chatExecutionService, configs, messages) },
            saveCandidates = { projectId, conversationId, userId, candidates ->
                userMemoryRepository.saveCandidates(projectId, conversationId, userId, candidates)
            }
        )
    }
    internal val chatRequestContextAssembler: ChatRequestContextAssembler by lazy {
        ChatRequestContextAssembler(
            retrieveMemory = conversationContextRepository::retrieveMemory,
            retrieveKnowledge = conversationContextRepository::retrieveKnowledge,
            retrieveUserMemory = { projectId, prompt -> userMemoryRepository.recall(projectId, prompt).context }
        )
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreRecoveryMutex = Mutex()
    @Volatile private var restoreRecoveryComplete = false

    suspend fun ensureBackupRestoreRecovered() {
        restoreRecoveryMutex.withLock {
            if (!restoreRecoveryComplete || localBackupRepository.hasPendingRestoreJournal()) {
                localBackupRepository.recoverInterruptedRestore(getSharedPreferences("nestro_local", MODE_PRIVATE))
                restoreRecoveryComplete = !localBackupRepository.hasPendingRestoreJournal()
            }
        }
    }

    val agentTaskHandlers: AgentTaskHandlerRegistry by lazy {
        AgentTaskHandlerRegistry().apply {
            register("phone_agent") { taskContext ->
                val dao = database.dao()
                val task = dao.phoneAgentTask(taskContext.taskId) ?: error("Phone Agent 任务不存在")
                val preferences = getSharedPreferences("nestro_local", MODE_PRIVATE)
                val result = PhoneAgentAgent.run(
                    this@NestroApplication,
                    dao,
                    taskContext.taskId,
                    preferences.getBoolean(PhoneAgentWorkScheduler.afterHandoffKey(taskContext.taskId), false),
                    preferences.getBoolean(PhoneAgentWorkScheduler.allowRetryKey(taskContext.taskId), false)
                )
                val store = RoomAgentTaskStateStore(dao)
                val current = store.get(taskContext.taskId)
                val status = when (result.outcome) {
                    PhoneAgentExecutor.Outcome.COMPLETED -> AgentTaskStatus.COMPLETED
                    PhoneAgentExecutor.Outcome.PAUSED -> AgentTaskStatus.PAUSED
                    PhoneAgentExecutor.Outcome.WAITING_USER -> AgentTaskStatus.WAITING_FOR_USER
                    PhoneAgentExecutor.Outcome.FAILED -> AgentTaskStatus.FAILED
                    PhoneAgentExecutor.Outcome.CANCELLED -> AgentTaskStatus.CANCELLED
                }
                if (current != null) {
                    store.put(current.copy(
                        status = status,
                        currentStep = dao.phoneAgentTask(taskContext.taskId)?.currentStep ?: task.currentStep,
                        lastError = if (status in setOf(AgentTaskStatus.PAUSED, AgentTaskStatus.WAITING_FOR_USER, AgentTaskStatus.FAILED)) result.message.take(2_000) else "",
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            }
        }
    }

    val agentTaskCoordinator: AgentTaskCoordinator by lazy {
        val dao = database.dao()
        AgentTaskCoordinator(
            states = RoomAgentTaskStateStore(dao),
            handlers = agentTaskHandlers,
            scope = applicationScope,
            traces = RoomAgentTaskTraceStore(dao)
        )
    }

    val phoneAgentTaskService: PhoneAgentTaskService by lazy {
        PhoneAgentTaskService(this, database, agentTaskCoordinator)
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            ensureBackupRestoreRecovered()
            agentTaskCoordinator.recoverAfterProcessRestart(externallyScheduledOwners = setOf("phone_agent"))
            val activePhoneTaskIds = database.dao().allPhoneAgentTasks()
                .filter { it.status in setOf(PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_RUNNING) }
                .map { it.id }
                .toSet()
            PhoneAgentWorkScheduler.reconcilePending(this@NestroApplication, activePhoneTaskIds)
        }
    }
}
