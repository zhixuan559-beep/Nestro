package app.nestro.data

import android.content.SharedPreferences
import app.nestro.agent.AgentApprovalCoordinator
import app.nestro.agent.AgentApprovalDecision
import app.nestro.agent.AgentApprovalPendingException
import app.nestro.model.ChatExecutionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Runs saved prompt/expert/skill workflows from scheduled calendar automations.
 * External tool execution remains with the top-level chat dispatcher; steps marked
 * for approval use the durable approval scope of the owning calendar task.
 */
internal class CalendarWorkflowRunner(
    private val preferences: SharedPreferences,
    private val coordinator: WorkflowExecutionCoordinator,
    private val approvalCoordinator: AgentApprovalCoordinator,
    private val chatExecutionService: ChatExecutionService,
    private val modelConfigs: List<ModelConfig>,
    private val projectId: String,
    private val conversationId: String,
    private val requestId: String,
    private val onUsage: (ModelConfig, Int, Int) -> Unit,
    private val onProviderUsage: (ModelConfig, Int, Int) -> Unit
) {
    private data class Resources(
        val experts: Set<String>,
        val expertPrompts: Map<String, String>,
        val expertProfiles: Map<String, ExpertProfile>,
        val skills: Map<String, String>,
        val disabledSkills: Set<String>,
        val skillMetadata: Map<String, String>
    )

    fun visibleWorkflows(): List<WorkflowDefinition> {
        val scopes = ProjectResourceScopeStore.load(preferences)
        return WorkflowDefinitionCodec.decode(preferences.getString("workflows_v1", "[]") ?: "[]")
            .filter { ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.WORKFLOW, it.id, projectId) }
    }

    suspend fun run(workflowId: String, input: String): String {
        require(modelConfigs.isNotEmpty()) { "当前没有可用文本模型，无法运行工作流" }
        require(input.isNotBlank() && input.length <= 12_000) { "工作流输入为空或过长" }
        val scopes = ProjectResourceScopeStore.load(preferences)
        val workflow = visibleWorkflows().firstOrNull { it.id == workflowId }
            ?: error("当前项目找不到该工作流，或该工作流不在项目可见范围内")
        WorkflowDefinitionCodec.validate(workflow)
        val resources = loadResources(scopes)
        workflow.steps.filter { it.enabled }.forEach { step ->
            when (step.kind) {
                WorkflowStepKind.PROMPT -> Unit
                WorkflowStepKind.EXPERT -> require(
                    step.targetId in resources.experts &&
                        resources.expertProfiles[step.targetId]?.enabled != false &&
                        ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.EXPERT, step.targetId, projectId)
                ) { "工作流步骤“${step.title}”引用了当前项目不可用的专家：${step.targetId}" }
                WorkflowStepKind.SKILL -> require(
                    step.targetId in resources.skills &&
                        step.targetId !in resources.disabledSkills &&
                        ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.SKILL, step.targetId, projectId)
                ) { "工作流步骤“${step.title}”引用了当前项目不可用或已停用的技能：${step.targetId}" }
            }
        }

        val startedAt = System.currentTimeMillis()
        val taskId = UUID.nameUUIDFromBytes("$requestId:workflow:$workflowId".toByteArray()).toString()
        return try {
            val result = coordinator.execute(
                workflow = workflow,
                input = input,
                taskId = taskId,
                projectId = projectId,
                conversationId = conversationId,
                invokeModel = { step, prompt ->
                    val profile = if (step.kind == WorkflowStepKind.EXPERT) resources.expertProfiles[step.targetId] else null
                    val roleContext = when (step.kind) {
                        WorkflowStepKind.PROMPT -> ""
                        WorkflowStepKind.EXPERT -> {
                            val role = resources.expertPrompts[step.targetId].orEmpty().ifBlank {
                                profile?.description?.ifBlank { null }
                                    ?: ExpertTeamDefaults.rolePrompt(step.targetId).ifBlank { "你是${step.targetId}，请按步骤指令完成任务。" }
                            }
                            val skills = ExpertProfileCodec.skillContext(
                                profile, resources.skills, resources.disabledSkills, resources.skillMetadata
                            )
                            listOf(role, skills).filter { it.isNotBlank() }.joinToString("\n\n")
                        }
                        WorkflowStepKind.SKILL -> resources.skills[step.targetId].orEmpty()
                    }
                    val systemPrompt = listOf(
                        "你是 Nestro 的工作流步骤执行模型。严格完成当前步骤，不要声称执行了未提供的外部工具；输出应可供后续步骤直接使用。",
                        roleContext
                    ).filter { it.isNotBlank() }.joinToString("\n\n").take(18_000)
                    withContext(Dispatchers.IO) {
                        chatExecutionService.completeWithFallback(
                            modelConfigs,
                            listOf(ChatMessage(UUID.randomUUID().toString(), "user", prompt)),
                            systemPrompt,
                            connectTimeoutMillis = 15_000,
                            readTimeoutMillis = 25_000,
                            temperature = profile?.temperature,
                            maxTokens = profile?.maxTokens,
                            onUsage = onUsage,
                            onProviderUsage = onProviderUsage
                        ).take(12_000)
                    }
                },
                requestApproval = { step, previousOutput ->
                    when (approvalCoordinator.authorize(
                        requestId,
                        "workflow.step.${step.id}".take(100),
                        "日历自动工作流“${workflow.name}”的步骤“${step.title}”需要确认。前序结果：${previousOutput.take(800)}"
                    )) {
                        AgentApprovalDecision.AUTHORIZE_ONCE,
                        AgentApprovalDecision.AUTHORIZE_TASK -> true
                        AgentApprovalDecision.REJECT -> false
                        null -> throw AgentApprovalPendingException("工作流步骤等待用户审批：${step.title}")
                    }
                }
            )
            persistHistory(
                WorkflowRunRecord(
                    workflowId = workflow.id,
                    workflowName = workflow.name,
                    input = input,
                    startedAt = startedAt,
                    status = "COMPLETED",
                    output = result.output,
                    stepRuns = result.stepRuns,
                    projectId = projectId
                )
            )
            result.output.take(20_000)
        } catch (pending: AgentApprovalPendingException) {
            persistHistory(
                WorkflowRunRecord(
                    workflowId = workflow.id,
                    workflowName = workflow.name,
                    input = input,
                    startedAt = startedAt,
                    status = "WAITING_FOR_USER",
                    output = pending.message.orEmpty(),
                    projectId = projectId
                )
            )
            throw pending
        } catch (error: Exception) {
            persistHistory(
                WorkflowRunRecord(
                    workflowId = workflow.id,
                    workflowName = workflow.name,
                    input = input,
                    startedAt = startedAt,
                    status = "FAILED",
                    output = (error.message ?: error.javaClass.simpleName).take(2_000),
                    projectId = projectId
                )
            )
            throw error
        }
    }

    private fun loadResources(scopes: Map<String, Set<String>>): Resources {
        val experts = runCatching {
            val rows = JSONArray(preferences.getString("experts", """["文案官","选题狐","代码佬"]""") ?: "[]")
            (0 until rows.length()).map { rows.getString(it) }.filter { it.isNotBlank() }
                .filter { ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.EXPERT, it, projectId) }
                .toSet()
        }.getOrDefault(emptySet())
        val expertPrompts = readStringMap("expert_prompts", 8_000)
        val expertProfiles = ExpertProfileCodec.decode(preferences.getString("expert_profiles_v1", "{}") ?: "{}")
        val skills = readStringMap("skills", 8_000).filterKeys {
            ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.SKILL, it, projectId)
        }
        val disabledSkills = runCatching {
            val rows = JSONArray(preferences.getString("disabled_skills_v1", "[]") ?: "[]")
            (0 until rows.length()).map { rows.getString(it) }.toSet()
        }.getOrDefault(skills.keys)
        val skillMetadata = readStringMap("skill_metadata_v1", 4_000)
        return Resources(experts, expertPrompts, expertProfiles, skills, disabledSkills, skillMetadata)
    }

    private fun readStringMap(key: String, maxValueLength: Int): Map<String, String> = runCatching {
        val json = JSONObject(preferences.getString(key, "{}") ?: "{}")
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                put(name, json.optString(name).take(maxValueLength))
            }
        }
    }.getOrDefault(emptyMap())

    private fun persistHistory(record: WorkflowRunRecord) {
        val key = "workflow_run_history_v1"
        val current = WorkflowRunHistoryCodec.decode(preferences.getString(key, "[]") ?: "[]")
        check(preferences.edit().putString(key, WorkflowRunHistoryCodec.encode((listOf(record) + current).take(200))).commit()) {
            "工作流运行记录保存失败"
        }
    }
}
