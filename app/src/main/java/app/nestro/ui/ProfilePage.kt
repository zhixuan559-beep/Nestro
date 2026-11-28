package app.nestro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nestro.data.ArtifactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 改版第 c 步：资料页照设计图 03——浅灰底＋白色大圆角卡片，收纳全部非对话功能：
 * 模型与用量、专家、技能、应用、电话、电脑（虚拟屏）、自动化、记忆、文件、自动备份、设置，
 * 外加过渡期的最近对话/项目空间（抽屉「更多」组已撤到这里），一项不丢。
 * 计数全部取真数据（专家/技能/自动化取内存态；文件数读产物库；应用数取已启用的 MCP 服务）；
 * 没有额度概念，不编「剩余百分比」——模型行只写当前模型名与已请求次数。
 * 资料卡头像显示当前形象（默认卡皮巴拉）；换形象是第 f 步虚拟形象系统的事，这里只展示。
 */
@Composable
internal fun ProfilePage(
    assistantName: String,
    mascot: String,
    modelSubtitle: String,
    expertCount: Int,
    skillCount: Int,
    connectedAppCount: Int,
    workflowCount: Int,
    projectId: String,
    autoBackupEnabled: Boolean,
    onAutoBackupChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    var artifactCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(projectId) {
        artifactCount = withContext(Dispatchers.IO) {
            runCatching { ArtifactStore.list(context, projectId).size }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Card2).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        // 资料卡：头像是当前形象（默认卡皮巴拉），点进专家页看主助手详情。
        Surface(
            onClick = { onNavigate("experts") },
            color = Card,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "主助手资料" }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                MascotAvatar(mascot = mascot, avatar = "✦", name = assistantName, size = 54.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        assistantName,
                        color = Ink,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text("你的主助手 · 本机运行", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
            }
        }
        ProfileCard {
            ProfileRow(
                icon = Icons.Outlined.Psychology,
                title = "模型与用量",
                value = modelSubtitle,
                onClick = { onNavigate("models") }
            )
        }
        ProfileCard {
            ProfileRow(
                icon = Icons.Outlined.Person,
                title = "专家",
                value = "$expertCount 位",
                onClick = { onNavigate("experts") }
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Outlined.Extension,
                title = "技能",
                value = "$skillCount 个",
                onClick = { onNavigate("skills") }
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Outlined.Apps,
                title = "应用",
                value = if (connectedAppCount > 0) "已连接 $connectedAppCount 个" else "尚未连接",
                onClick = { onNavigate("tools") }
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Outlined.Call,
                title = "电话",
                value = "AI 语音通话",
                onClick = { onNavigate("voice") }
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Outlined.Computer,
                title = "电脑（虚拟屏）",
                value = null,
                onClick = { onNavigate("phone-agent") }
            )
        }
        ProfileCard {
            ProfileRow(
                icon = Icons.Outlined.Schedule,
                title = "自动化",
                value = "$workflowCount 个",
                onClick = { onNavigate("workflows") }
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Outlined.Storage,
                title = "记忆",
                value = null,
                onClick = { onNavigate("memory") }
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Outlined.Folder,
                title = "它生成的文件",
                value = artifactCount?.let { "$it 个" },
                onClick = { onNavigate("artifacts") }
            )
            ProfileDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(19.dp)
                )
                Text(
                    "自动备份",
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoBackupEnabled,
                    onCheckedChange = onAutoBackupChange,
                    modifier = Modifier.semantics { contentDescription = "自动备份开关" }
                )
            }
        }
        ProfileCard {
            ProfileRow(
                icon = Icons.Outlined.Settings,
                title = "设置",
                value = null,
                onClick = { onNavigate("settings") }
            )
        }
        ProfileCard {
            ProfileRow(
                icon = Icons.Outlined.History,
                title = "最近对话",
                value = null,
                onClick = { onNavigate("conversations") }
            )
            ProfileDivider()
            ProfileRow(
                icon = Icons.Outlined.GridView,
                title = "项目空间",
                value = null,
                onClick = { onNavigate("spaces") }
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 资料页的白色大圆角卡片（设计图 03：圆角 22dp、轻阴影）。 */
@Composable
private fun ProfileCard(content: @Composable () -> Unit) {
    Surface(
        color = Card,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            content()
        }
    }
}

@Composable
private fun ProfileDivider() {
    HorizontalDivider(color = Line)
}

/** 资料页的一行：图标＋标题＋右值＋ chevron，整行可点。 */
@Composable
private fun ProfileRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Card, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(19.dp))
            Text(
                title,
                color = Ink,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Text(value, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
        }
    }
}
