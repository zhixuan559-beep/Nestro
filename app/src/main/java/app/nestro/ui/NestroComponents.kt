package app.nestro.ui

import app.nestro.data.ChatMessage
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun ChatMessage.allVersions(): List<String> = when {
    versions.isEmpty() -> listOf(content)
    activeVersion >= versions.size -> (versions + content).distinct().takeLast(20)
    else -> versions.distinct().takeLast(20)
}

@Composable
internal fun AnimatedStatusText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = Int.MAX_VALUE
) {
    Crossfade(
        targetState = text,
        animationSpec = tween(NestroMotion.FAST),
        label = "status-text-transition"
    ) { currentText ->
        if (currentText.isNotBlank()) {
            Text(currentText, modifier = modifier, color = color, style = style, maxLines = maxLines)
        }
    }
}

@Composable
internal fun DrawerItem(icon: String, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text("$icon   $label", color = Ink) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Bg,
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
internal fun MessageBubble(message: ChatMessage, onSaveArtifact: ((String) -> Unit)? = null, onEdit: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null, onRetry: (() -> Unit)? = null, onVersionChange: ((Int) -> Unit)? = null, onOpenVideo: ((String) -> Unit)? = null, onOpenMediaSetup: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val user = message.role == "user"
    val videoMarker = Regex("""\[NESTRO_VIDEO_ASSET:([A-Fa-f0-9-]{36})\]""")
    val setupMarker = "[NESTRO_MEDIA_PROVIDER_SETUP]"
    val videoAssetId = videoMarker.find(message.content)?.groupValues?.getOrNull(1)
    val visibleContent = message.content.replace(videoMarker, "").replace(setupMarker, "").trim()
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Surface(
                color = if (user) Card2 else Bg,
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
        modifier.background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Nost",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                modifier = Modifier.weight(1f)
            )
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭菜单", tint = Ink)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        DrawerItem("＋", "新建对话") { onNavigate("home") }
        DrawerItem("◷", "最近对话") { onNavigate("conversations") }
        DrawerItem("▣", "项目空间") { onNavigate("spaces") }
        HorizontalDivider(color = Line)
        DrawerItem("✧", "专家与专家团") { onNavigate("experts") }
        DrawerItem("⌘", "工具与扩展") { onNavigate("tools") }
        DrawerItem("⚙", "设置") { onNavigate("settings") }
        Spacer(Modifier.height(12.dp))
        Text("本地优先 · 免注册", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun SimplePage(title: String, description: String) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
}
