package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
        onStepCompleted: suspend (Int, WorkflowStepRun, String) -> Unit = { _, _, _ -> },
        requestApproval: suspend (WorkflowStep, String) -> Boolean = { _, _ -> false },
        toolCatalog: AgentToolCatalog? = null,
        invokeModelWithTools: (suspend (WorkflowStep, String, AgentToolDiscovery?) -> String)? = null
    ): WorkflowRunResult {
        require(input.isNotBlank() && input.length <= 12_000) { "工作流输入为空或过长" }
        val workflowFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(WorkflowDefinitionCodec.encode(listOf(workflow)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val inputFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val savedState = taskCoordinator.state(taskId)
        val savedPayload = savedState?.checkpoint
            ?.takeIf { it.startsWith("workflow_run:") }
            ?.removePrefix("workflow_run:")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (savedPayload != null) {
            require(savedPayload.optInt("schemaVersion") == 1 &&
                savedPayload.optString("workflowId") == workflow.id &&
                savedPayload.optString("workflowFingerprint") == workflowFingerprint) {
                "持久化工作流检查点与当前工作流定义不匹配；请恢复原工作流或新建运行"
            }
            require(savedPayload.optString("inputFingerprint") == inputFingerprint) {
                "持久化工作流输入与当前输入不一致；请恢复原输入或新建运行"
            }
        }
        val resumeRuns = savedPayload?.optJSONArray("stepRuns")?.let { rows ->
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                WorkflowStepRun(
                    stepId = row.optString("stepId"),
                    title = row.optString("title"),
                    status = row.optString("status"),
                    attempts = row.optInt("attempts").coerceIn(0, 100),
                    output = row.optString("output").take(1_200),
                    error = row.optString("error").take(300),
                    iterations = row.optInt("iterations", 1).coerceIn(0, 3)
                )
            }
        }.orEmpty()
        require(resumeRuns.map { it.stepId }.distinct().size == resumeRuns.size &&
            resumeRuns.all { row -> workflow.steps.any { it.id == row.stepId } &&
                row.status in setOf("success", "skipped", "failed") }) {
            "工作流检查点包含重复或无效步骤，已停止恢复"
        }
        val resumeOutput = savedPayload?.optString("previousOutput").orEmpty().take(24_000)
        val initialCheckpoint = "workflow_run:" + JSONObject()
            .put("schemaVersion", 1)
            .put("workflowId", workflow.id)
            .put("workflowFingerprint", workflowFingerprint)
            .put("inputFingerprint", inputFingerprint)
            .put("completedStepIndex", 0)
            .put("previousOutput", "")
            .put("stepRuns", JSONArray())
            .toString()

        return taskCoordinator.runInline(
            taskId = taskId,
            owner = "workflow",
            initialCheckpoint = initialCheckpoint
        ) { taskContext ->
            val completedRuns = resumeRuns.toMutableList()
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
                onStep = onStep,
                onStepCompleted = { index, stepRun, output ->
                    completedRuns += stepRun
                    val checkpoint = JSONObject()
                        .put("schemaVersion", 1)
                        .put("workflowId", workflow.id)
                        .put("workflowFingerprint", workflowFingerprint)
                        .put("input", input.take(12_000))
                        .put("completedStepIndex", index)
                        .put("previousOutput", output.take(24_000))
                        .put("stepRuns", JSONArray().apply {
                            completedRuns.take(20).forEach { row ->
                                put(JSONObject()
                                    .put("stepId", row.stepId)
                                    .put("title", row.title)
                                    .put("status", row.status)
                                    .put("attempts", row.attempts)
                                    .put("output", if (
                                        workflow.steps.firstOrNull { it.id == stepRun.stepId }?.parallelGroup?.isNotBlank() == true &&
                                        workflow.steps.firstOrNull { it.id == row.stepId }?.parallelGroup ==
                                            workflow.steps.firstOrNull { it.id == stepRun.stepId }?.parallelGroup
                                    ) row.output.take(8_000) else "")
                                    .put("error", row.error.take(300))
                                    .put("iterations", row.iterations))
                            }
                        }).toString()
                    taskContext.checkpoint("workflow_run:" + checkpoint, index)
                    onStepCompleted(index, stepRun, output)
                },
                requestApproval = requestApproval,
                resumeCompletedRuns = resumeRuns,
                resumeOutput = resumeOutput
            )
            taskContext.checkpoint("workflow:" + workflow.id + ":finished", result.completedSteps)
            result
        }
    }
}
