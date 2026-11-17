package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
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
        onStep: suspend (Int, WorkflowStep) -> Unit = { _, _ -> }
    ): WorkflowRunResult = taskCoordinator.runInline(
        taskId = taskId,
        owner = "workflow",
        initialCheckpoint = "workflow:" + workflow.id + ":queued"
    ) { taskContext ->
        taskContext.checkpoint("workflow:" + workflow.id + ":running", 0)
        val result = WorkflowEngine.run(
            workflow = workflow,
            userInput = input,
            invokeModel = invokeModel,
            onStep = onStep
        )
        taskContext.checkpoint("workflow:" + workflow.id + ":finished", result.completedSteps)
        result
    }
}
