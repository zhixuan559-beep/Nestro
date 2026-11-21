package app.nestro.ui

import app.nestro.data.ChatMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun ChatMessage.allVersions(): List<String> = when {
    versions.isEmpty() -> listOf(content)
    activeVersion >= versions.size -> (versions + content).distinct().takeLast(20)
    else -> versions.distinct().takeLast(20)
}

@Composable
internal fun DrawerItem(icon: String, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text("$icon   $label", color = Ink) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Card2,
            unselectedTextColor = Ink,
            selectedContainerColor = Card2,
            selectedTextColor = Ink
        )
    )
}

@Composable
internal fun Suggestion(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Card, shape = RoundedCornerShape(50)) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ink)
    }
}

@Composable
internal fun MessageBubble(message: ChatMessage, onSaveArtifact: ((String) -> Unit)? = null, onEdit: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null, onRetry: (() -> Unit)? = null, onVersionChange: ((Int) -> Unit)? = null, onOpenVideo: ((String) -> Unit)? = null, onOpenMediaSetup: (() -> Unit)? = null) {
    val user = message.role == "user"
    val videoMarker = Regex("""\[NESTRO_VIDEO_ASSET:([A-Fa-f0-9-]{36})\]""")
    val setupMarker = "[NESTRO_MEDIA_PROVIDER_SETUP]"
    val videoAssetId = videoMarker.find(message.content)?.groupValues?.getOrNull(1)
    val visibleContent = message.content.replace(videoMarker, "").replace(setupMarker, "").trim()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Surface(
                color = if (user) androidx.compose.ui.graphics.Color(0xFF173B30) else Card,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(visibleContent, color = Ink, modifier = Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (!user && setupMarker in message.content && onOpenMediaSetup != null) {
                TextButton(
                    onClick = onOpenMediaSetup,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = Mint, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("配置视频模型 API", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!user && videoAssetId != null && onOpenVideo != null) {
                TextButton(
                    onClick = { onOpenVideo(videoAssetId) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Outlined.PlayCircle, contentDescription = null, tint = Mint, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("查看生成视频", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!user && message.content.isNotBlank() && onSaveArtifact != null) {
                TextButton(onClick = { onSaveArtifact.invoke(visibleContent) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
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
    onNavigate: (String) -> Unit,
    onClose: (() -> Unit)? = null
) {
    Column(
        modifier.background(Card).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Nestro",
                style = MaterialTheme.typography.headlineSmall,
                color = Mint,
                modifier = Modifier.weight(1f)
            )
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭菜单", tint = Ink)
                }
            }
        }
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
        DrawerItem("✂", "智能剪辑") { onNavigate("media-editor") }
        DrawerItem("▦", "日历") { onNavigate("calendar") }
        DrawerItem("⌘", "能力目录") { onNavigate("capabilities") }
        DrawerItem("⛓", "MCP 工具中心") { onNavigate("mcp") }
        DrawerItem("⑂", "Git 工作台") { onNavigate("git") }
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
