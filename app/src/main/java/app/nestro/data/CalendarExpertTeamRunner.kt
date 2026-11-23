package app.nestro.data

import android.content.SharedPreferences
import app.nestro.model.ChatExecutionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Executes model-planned expert DAGs from scheduled calendar automations.
 * It deliberately uses the same expert directory, project scopes, checkpoints,
 * and collaboration event codec as interactive expert-team execution.
 */
internal class CalendarExpertTeamRunner(
    private val preferences: SharedPreferences,
    private val coordinator: ExpertTeamExecutionCoordinator,
    private val chatExecutionService: ChatExecutionService,
    private val modelConfigs: List<ModelConfig>,
    private val projectId: String,
    private val conversationId: String,
    private val parentTaskId: String,
    private val onUsage: (ModelConfig, Int, Int) -> Unit,
    private val onProviderUsage: (ModelConfig, Int, Int) -> Unit
) {
    private data class Directory(
        val names: List<String>,
        val prompts: Map<String, String>,
        val profiles: Map<String, ExpertProfile>,
        val skills: Map<String, String>,
        val disabledSkills: Set<String>,
        val skillMetadata: Map<String, String>
    )

    private fun directory(): Directory {
        val names = runCatching {
            val rows = JSONArray(preferences.getString("experts", """["文案官","选题狐","代码佬"]""") ?: "[]")
            (0 until rows.length()).map { rows.getString(it).trim() }.filter { it.isNotBlank() }.distinct()
        }.getOrDefault(emptyList())
        val prompts = runCatching {
            val json = JSONObject(preferences.getString("expert_prompts", "{}") ?: "{}")
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key).take(8_000))
                }
            }
        }.getOrDefault(emptyMap())
        val profiles = ExpertProfileCodec.decode(preferences.getString("expert_profiles_v1", "{}") ?: "{}")
        val skills = DEFAULT_SKILLS + runCatching {
            val json = JSONObject(preferences.getString("skills", "{}") ?: "{}")
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key).take(8_000))
                }
            }
        }.getOrDefault(emptyMap())
        val disabledSkills = runCatching {
            val rows = JSONArray(preferences.getString("disabled_skills_v1", "[]") ?: "[]")
            (0 until rows.length()).map { rows.getString(it) }.toSet()
        }.getOrDefault(skills.keys)
        val skillMetadata = runCatching {
            val json = JSONObject(preferences.getString("skill_metadata_v1", "{}") ?: "{}")
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key).take(4_000))
                }
            }
        }.getOrDefault(emptyMap())
        return Directory(names, prompts, profiles, skills, disabledSkills, skillMetadata)
    }

    fun availableMemberNames(): List<String> = runCatching {
        val directory = directory()
        val scopes = ProjectResourceScopeStore.load(preferences)
        directory.names.filter { name ->
            directory.profiles[name]?.enabled != false &&
                ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.EXPERT, name, projectId)
        }
    }.getOrDefault(emptyList())

    fun hasAvailableTeam(): Boolean = availableMemberNames().size >= 2

    suspend fun run(systemContext: String, arguments: Map<String, String>): String {
        require(modelConfigs.isNotEmpty()) { "当前没有可用的文本模型配置，无法执行专家项目" }
        val directory = directory()
        val scopes = ProjectResourceScopeStore.load(preferences)
        val availableNames = directory.names.filter { name ->
            directory.profiles[name]?.enabled != false &&
                ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.EXPERT, name, projectId)
        }.toSet()
        val requestedNames = parseStringArray(arguments["member_names"].orEmpty())
        val chosenNames = requestedNames.distinct().filter { it in availableNames }.take(4)
        require(chosenNames.size in 2..4) {
            "当前项目至少需要 2 位已启用且有权访问的专家；模型选择的成员不符合项目资源范围。"
        }

        val planJson = JSONObject(arguments["plan"] ?: error("模型未提供专家项目工作包计划"))
        val packageRows = planJson.getJSONArray("packages")
        val plan = ExpertProjectPlan(
            projectGoal = planJson.optString("project_goal"),
            packages = (0 until packageRows.length()).map { index ->
                val row = packageRows.getJSONObject(index)
                val dependencies = row.optJSONArray("depends_on") ?: JSONArray()
                ExpertWorkPackage(
                    id = row.optString("id"),
                    memberName = row.optString("member_name"),
                    instruction = row.optString("instruction"),
                    dependsOn = (0 until dependencies.length()).map { dependencies.getString(it) },
                    acceptanceCriteria = row.optString("acceptance_criteria")
                )
            },
            finalAcceptanceCriteria = planJson.optString(
                "final_acceptance_criteria",
                "所有工作包完成，主模型逐项核对最终验收标准并整合交付。"
            )
        )
        ExpertProjectPlanExecutor.validate(plan, chosenNames.toSet())

        val members = chosenNames.associateWith { name ->
            val profile = directory.profiles[name]
            val rolePrompt = directory.prompts[name].orEmpty().ifBlank {
                profile?.description?.ifBlank { null } ?: ExpertTeamDefaults.rolePrompt(name).ifBlank {
                    "你是$name，请根据工作包指令完成专业分析，提交可核验结果、依据、风险和未解决项。"
                }
            }
            val attachedSkills = ExpertProfileCodec.skillContext(
                profile,
                directory.skills,
                directory.disabledSkills,
                directory.skillMetadata
            )
            ExpertTeamMemberTask(
                name = name,
                rolePrompt = listOf(rolePrompt, attachedSkills).filter { it.isNotBlank() }.joinToString("\n\n")
            )
        }

        val teamName = arguments["team_name"]?.trim()?.take(40)?.ifBlank { null } ?: "日历自动专家团"
        val teamDescription = arguments["team_description"]?.trim()?.take(300)?.ifBlank { null }
            ?: systemContext.take(300)
        val planFingerprint = JSONObject(arguments["plan"] ?: "").toString()
        val teamId = UUID.nameUUIDFromBytes("$projectId:$parentTaskId:$teamName:$planFingerprint".toByteArray()).toString()
        val childTaskId = UUID.nameUUIDFromBytes("$parentTaskId:expert-project:$planFingerprint".toByteArray()).toString()
        val result = coordinator.executeProjectPlan(
            teamId = teamId,
            teamName = teamName,
            teamDescription = teamDescription,
            plan = plan,
            members = members,
            projectId = projectId,
            conversationId = conversationId,
            taskId = childTaskId,
            eventSink = ExpertTeamEventSink { event ->
                withContext(Dispatchers.IO) {
                    val key = "expert_team_events_v1"
                    val existing = ExpertTeamCollaborationEventCodec.decode(
                        preferences.getString(key, "[]") ?: "[]"
                    )
                    check(preferences.edit().putString(key, ExpertTeamCollaborationEventCodec.encode(existing + event)).commit()) {
                        "专家协作事件保存失败"
                    }
                }
            },
            invokeMember = { member, memberPrompt ->
                val memberSystemContext = listOf(systemContext, member.rolePrompt)
                    .filter { it.isNotBlank() }.joinToString("\n\n").take(18_000)
                withContext(Dispatchers.IO) {
                    chatExecutionService.completeWithFallback(
                        modelConfigs,
                        listOf(ChatMessage(UUID.randomUUID().toString(), "user", memberPrompt)),
                        memberSystemContext,
                        connectTimeoutMillis = 15_000,
                        readTimeoutMillis = 25_000,
                        temperature = directory.profiles[member.name]?.temperature,
                        maxTokens = directory.profiles[member.name]?.maxTokens,
                        onUsage = onUsage,
                        onProviderUsage = onProviderUsage
                    ).take(12_000)
                }
            }
        )

        return buildString {
            append("专家团：").append(teamName).append("\n")
            append("项目目标：").append(plan.projectGoal).append("\n")
            append("最终验收标准：").append(plan.finalAcceptanceCriteria).append("\n")
            result.packageResults.forEach { item ->
                append("\n工作包 ").append(item.packageId)
                    .append(" · ").append(item.memberName)
                    .append(" · ").append(item.status).append("\n")
                if (item.output.isNotBlank()) append(item.output.take(4_000)).append("\n")
                if (item.error.isNotBlank()) append("错误：").append(item.error.take(500)).append("\n")
            }
            append("\n以上为专家工作包结果；主模型仍须按最终验收标准交叉审查，不得直接把提交状态视为验收通过。")
            append("\n本次专家成员通过文本模型执行，未开放成员内部的工具调用。")
        }.take(12_000)
    }

    private fun parseStringArray(raw: String): List<String> = runCatching {
        val rows = JSONArray(raw)
        (0 until rows.length()).map { rows.getString(it).trim() }.filter { it.isNotBlank() }
    }.getOrElse {
        raw.split(Regex("[,，、;；]")).map { it.trim() }.filter { it.isNotBlank() }
    }
}
