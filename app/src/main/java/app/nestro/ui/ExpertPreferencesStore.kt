package app.nestro.ui

import app.nestro.NestroApplication
import app.nestro.model.ModelConfigurationRepository
import app.nestro.model.ModelRegistryCodec
import app.nestro.data.ModelConfig
import app.nestro.data.ChatMessage
import app.nestro.data.ConversationRecord
import app.nestro.data.ConversationPreferencesStore
import app.nestro.data.ProjectRecord
import app.nestro.data.ProjectPreferencesStore
import app.nestro.data.OpenAiClient
import app.nestro.data.ConversationEntity
import app.nestro.data.ConversationListItem
import app.nestro.data.MessageEntity
import app.nestro.data.ProjectEntity
import app.nestro.data.ProjectSpaceRules
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.KnowledgeChunkEntity
import app.nestro.data.NestroDatabase
import app.nestro.data.KnowledgeStore
import app.nestro.data.ConversationMemoryStore
import app.nestro.data.ConversationRecallSource
import androidx.room.withTransaction
import app.nestro.phone.PhoneAgentService
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentExecutor
import app.nestro.phone.PhoneAgentWorkScheduler
import app.nestro.phone.PhoneAgentNotifications
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.data.PhoneAgentTaskEntity
import app.nestro.data.PhoneAgentTaskLogEntity
import app.nestro.data.UserMemoryStore
import app.nestro.data.MemoryCandidate
import app.nestro.data.SkillBundle
import app.nestro.data.SkillBundleParser
import app.nestro.data.ExpertBundle
import app.nestro.data.ExpertBundleParser
import app.nestro.data.LocalBackupCompatibility
import app.nestro.data.ModelProfileMetadata
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelUsageStats
import app.nestro.data.ModelUsageStatsCodec
import app.nestro.data.ChatRequestPolicy
import app.nestro.data.ChatEndpointPolicy
import app.nestro.data.ChatHttpTransport
import app.nestro.data.ChatHistoryItem
import app.nestro.data.ExpertCatalogRules
import app.nestro.data.ExpertProfile
import app.nestro.data.ExpertProfileCodec
import app.nestro.data.ExpertRevision
import app.nestro.data.ExpertRevisionCodec
import app.nestro.data.ExpertTemplate
import app.nestro.data.ExpertTemplateCatalog
import app.nestro.data.ExpertTemplateBundleCodec
import app.nestro.data.ExpertTemplateCodec
import app.nestro.data.ExpertTeamBundle
import app.nestro.data.ExpertTeamMemberBundle
import app.nestro.data.ExpertTeamBundleParser
import app.nestro.data.ExpertTeamEngine
import app.nestro.data.ExpertTeamDefaults
import app.nestro.data.ExpertTeamMemberTask
import app.nestro.data.ExpertTeamRunResult
import app.nestro.data.ExpertTeamRunHistoryCodec
import app.nestro.data.WorkflowDefinition
import app.nestro.data.WorkflowStep
import app.nestro.data.WorkflowStepKind
import app.nestro.data.WorkflowDefinitionCodec
import app.nestro.data.WorkflowEngine
import app.nestro.data.WorkflowRunHistoryCodec
import app.nestro.data.WorkflowRunRecord
import app.nestro.data.WorkflowExecutionException
import app.nestro.data.UserMemoryEntity
import app.nestro.ui.VoiceInputButton
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ExpertTeam(val id: String, val name: String, val description: String, val members: List<String>)

internal fun loadSkillMetadata(prefs: android.content.SharedPreferences): Map<String, String> {
    val raw = prefs.getString("skill_metadata_v1", "{}") ?: "{}"
    return runCatching {
        val json = JSONObject(raw)
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key)
            if (key.length <= 40 && value.length <= 4000) result[key] = value
        }
        result
    }.getOrDefault(emptyMap())
}

internal fun skillMetadataToJson(metadata: Map<String, String>): String =
    JSONObject().apply { metadata.forEach { (name, value) -> put(name, value) } }.toString()

internal fun loadDisabledSkills(prefs: android.content.SharedPreferences): Set<String> =
    runCatching {
        val array=JSONArray(prefs.getString("disabled_skills_v1","[]") ?: "[]")
        (0 until array.length()).map { array.getString(it) }.filter { it.length<=40 }.toSet()
    }.getOrDefault(emptySet())

internal fun disabledSkillsToJson(names:Set<String>):String = JSONArray(names.sorted()).toString()

internal fun loadExpertTeams(prefs: android.content.SharedPreferences): List<ExpertTeam> = runCatching {
    val array = JSONArray(prefs.getString("expert_teams_v1", "[]"))
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val members = item.optJSONArray("members") ?: JSONArray()
        ExpertTeam(
            id = item.optString("id"),
            name = item.optString("name"),
            description = item.optString("description"),
            members = (0 until members.length()).map { members.getString(it) }.distinct()
        ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() && it.members.size >= 2 }
    }
}.getOrDefault(emptyList())

internal fun expertTeamsToJson(teams: List<ExpertTeam>): String =
    JSONArray().apply {
        teams.forEach { team ->
            put(JSONObject().put("id", team.id).put("name", team.name).put("description", team.description)
                .put("members", JSONArray(team.members)))
        }
    }.toString()

internal fun loadExperts(prefs: android.content.SharedPreferences): List<String> = runCatching {
    val array = JSONArray(prefs.getString("experts", """["文案官","选题狐","代码佬"]"""))
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(listOf("文案官", "选题狐", "代码佬"))


internal fun loadExpertPrompts(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val raw = prefs.getString("expert_prompts", "{}") ?: "{}"
    val json = JSONObject(raw)
    buildMap { val keys = json.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, json.optString(key)) } }
}.getOrDefault(emptyMap())


internal fun expertPromptsToJson(prompts: Map<String, String>): String {
    val json = JSONObject()
    prompts.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}

internal val DEFAULT_SKILLS = linkedMapOf(
    "结构化总结" to "请提炼核心结论、关键事实、行动项与未解决问题。区分原文事实和你的推断，避免遗漏重要限制。",
    "精简改写" to "请在不改变原意的前提下压缩冗余表达。保留关键事实与语气，优先给出可直接使用的成稿。",
    "代码审查" to "请从正确性、安全性、边界条件、性能和可维护性审查代码。先指出确定的问题，再给最小修复建议，不要臆测未提供的上下文。"
)

internal fun loadSkills(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val json = JSONObject(prefs.getString("skills", "{}") ?: "{}")
    val loaded = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        loaded[name] = json.optString(name)
    }
    DEFAULT_SKILLS + loaded
}.getOrDefault(DEFAULT_SKILLS)

internal fun skillMapToJson(skills: Map<String, String>): String {
    val json = JSONObject()
    skills.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}
