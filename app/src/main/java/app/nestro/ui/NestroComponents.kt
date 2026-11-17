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

internal fun ChatMessage.allVersions(): List<String> = when {
    versions.isEmpty() -> listOf(content)
    activeVersion >= versions.size -> (versions + content).distinct().takeLast(20)
    else -> versions.distinct().takeLast(20)
}

@Composable
internal fun DrawerItem(icon: String, label: String, onClick: () -> Unit) {
    Surface(color = Card2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text("$icon   $label", color = Ink, modifier = Modifier.padding(14.dp))
    }
}

@Composable
internal fun Suggestion(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Card, shape = RoundedCornerShape(50)) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ink)
    }
}

@Composable
internal fun MessageBubble(message: ChatMessage, onSaveArtifact: ((String) -> Unit)? = null, onEdit: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null, onRetry: (() -> Unit)? = null, onVersionChange: ((Int) -> Unit)? = null) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Surface(
                color = if (user) androidx.compose.ui.graphics.Color(0xFF173B30) else Card,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(message.content, color = Ink, modifier = Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (!user && message.content.isNotBlank() && onSaveArtifact != null) {
                TextButton(onClick = { onSaveArtifact.invoke(message.content) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("存入产物库", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!user && message.content.startsWith("请求失败：") && onRetry != null) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("重试这条消息", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (user && onEdit != null) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!user && message.versions.isNotEmpty() && onVersionChange != null) {
                val versionCount = message.allVersions().size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = message.activeVersion > 0, onClick = { onVersionChange(message.activeVersion - 1) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("‹", color = Mint) }
                    Text("版本 ${message.activeVersion + 1}/$versionCount", color = Muted, style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = message.activeVersion < versionCount - 1, onClick = { onVersionChange(message.activeVersion + 1) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("›", color = Mint) }
                }
            }
            if (!user && onRegenerate != null) {
                TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新生成", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun NavigationDrawerContent(
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier.background(Card).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Nestro", style = MaterialTheme.typography.headlineSmall, color = Mint)
        Text("你的本机 AI 工作站", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        DrawerItem("⌂", "工作台") { onNavigate("home") }
        DrawerItem("☷", "对话历史") { onNavigate("conversations") }
        DrawerItem("✧", "全局记忆") { onNavigate("memory") }
        DrawerItem("▧", "本地知识库") { onNavigate("knowledge") }
        DrawerItem("⇢", "工作流") { onNavigate("workflows") }
        DrawerItem("▦", "项目空间") { onNavigate("spaces") }
        DrawerItem("⌁", "手机自动化") { onNavigate("phone") }
        DrawerItem("麦", "语音输入") { onNavigate("voice") }
        DrawerItem("▣", "产物库") { onNavigate("artifacts") }
        DrawerItem("⌘", "能力目录") { onNavigate("capabilities") }
        DrawerItem("✦", "我的专家") { onNavigate("experts") }
        DrawerItem("♧", "专家团队") { onNavigate("teams") }
        DrawerItem("◇", "能力中心") { onNavigate("skills") }
        DrawerItem("⚙", "模型管理") { onNavigate("models") }
        DrawerItem("▤", "数据与设置") { onNavigate("settings") }
        Spacer(Modifier.height(12.dp))
        Text("数据保存在本机", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun SimplePage(title: String, description: String) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
}
