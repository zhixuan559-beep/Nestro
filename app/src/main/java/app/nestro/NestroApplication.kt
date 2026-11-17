package app.nestro

import android.app.Application
import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentTaskHandlerRegistry
import app.nestro.agent.RoomAgentTaskStateStore
import app.nestro.agent.RoomAgentTaskTraceStore
import app.nestro.data.NestroDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns process-wide agent infrastructure so task execution is not tied to a
 * Compose screen or Activity lifecycle.
 */
class NestroApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val agentTaskHandlers: AgentTaskHandlerRegistry by lazy {
        AgentTaskHandlerRegistry()
    }

    val agentTaskCoordinator: AgentTaskCoordinator by lazy {
        val dao = NestroDatabase.get(this).dao()
        AgentTaskCoordinator(
            states = RoomAgentTaskStateStore(dao),
            handlers = agentTaskHandlers,
            scope = applicationScope,
            traces = RoomAgentTaskTraceStore(dao)
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            agentTaskCoordinator.recoverAfterProcessRestart()
        }
    }
}
