package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import java.util.UUID

/** Owns workflow task registration and shared AgentTaskCoordinator lifecycle. */
class WorkflowExecutionCoordinator(
    private val taskCoordinator: AgentTaskCoordinator
) {
    suspend fun execute(
        workflow: WorkflowDefinition,
        input: String,
        taskId: String = UUID.randomUUID().toString(),
        invokeModel: suspend (WorkflowStep, String) -> String,
        onStep: suspend (Int, WorkflowStep) -> Unit = { _, _ -> },
        toolCatalog: AgentToolCatalog? = null,
        invokeModelWithTools: (suspend (WorkflowStep, String, AgentToolDiscovery?) -> String)? = null
    ): WorkflowRunResult = taskCoordinator.runInline(
        taskId = taskId,
        owner = "workflow",
        initialCheckpoint = "workflow:" + workflow.id + ":queued"
    ) { taskContext ->
        taskContext.checkpoint("workflow:" + workflow.id + ":running", 0)
        val result = WorkflowEngine.run(
            workflow = workflow,
            userInput = input,
            invokeModel = { step, prompt ->
                val lease = AgentCapabilityRegistry.openLease(
                    taskId = taskId,
                    owner = "workflow",
                    operation = step.id,
                    catalog = toolCatalog
                )
                try {
                    if (lease != null && invokeModelWithTools == null) {
                        error("工作流步骤 ${step.title} 声明了工具能力，但未提供工具感知的模型调用适配器")
                    }
                    invokeModelWithTools?.invoke(step, prompt, lease?.access)
                        ?: invokeModel(step, prompt)
                } finally {
                    lease?.let { AgentToolLeaseRegistry.release(it.id) }
                }
            },
            onStep = onStep
        )
        taskContext.checkpoint("workflow:" + workflow.id + ":finished", result.completedSteps)
        result
    }
}
