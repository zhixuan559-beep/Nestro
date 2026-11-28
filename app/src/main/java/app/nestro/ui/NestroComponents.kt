package app.nestro.ui

import app.nestro.data.ChatMessage
import app.nestro.data.VoiceMessageMarker
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

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
internal fun MessageBubble(message: ChatMessage, onSaveArtifact: ((String) -> Unit)? = null, onEdit: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null, onRetry: (() -> Unit)? = null, onVersionChange: ((Int) -> Unit)? = null, onOpenVideo: ((String) -> Unit)? = null, onOpenMediaSetup: (() -> Unit)? = null, onPlayVoice: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {
    val user = message.role == "user"
    val videoMarker = Regex("""\[NESTRO_VIDEO_ASSET:([A-Fa-f0-9-]{36})\]""")
    val setupMarker = "[NESTRO_MEDIA_PROVIDER_SETUP]"
    val videoAssetId = videoMarker.find(message.content)?.groupValues?.getOrNull(1)
    val visibleContent = message.content.replace(videoMarker, "").replace(setupMarker, "").trim()
    // 改版新 a 步：语音消息（内容带 [NESTRO_VOICE:毫秒] 前缀）画成设计图 01 的蓝色波形气泡；
    // 点气泡朗读转写文本（原始录音未保留，TTS 回放是目前唯一能播的形态）。
    val voice = VoiceMessageMarker.parse(message.content)
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            if (voice != null && user) {
                val (durationMs, transcript) = voice
                Surface(
                    onClick = { onPlayVoice?.invoke(transcript) },
                    color = VoiceBubbleBlue,
                    shape = RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomEnd = 6.dp, bottomStart = 19.dp),
                    modifier = Modifier.width(250.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "播放语音",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Row(
                            Modifier.weight(1f).height(23.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            voiceWaveHeights(transcript).forEach { barHeight ->
                                Box(
                                    Modifier.width(3.dp).height(barHeight.dp)
                                        .background(Color.White, RoundedCornerShape(2.dp))
                                )
                            }
                        }
                        Text(
                            formatVoiceDuration(durationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            } else {
                Surface(
                    color = if (user) Ink else Card2,
                    shape = RoundedCornerShape(
                        topStart = 19.dp, topEnd = 19.dp,
                        bottomEnd = if (user) 6.dp else 19.dp,
                        bottomStart = if (user) 19.dp else 6.dp
                    ),
                    modifier = Modifier.widthIn(max = 320.dp)
                ) {
                    Text(visibleContent, color = if (user) Bg else Ink, modifier = Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium)
                }
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

/** 设计图 01 的语音气泡蓝。单色纪律下仅语音气泡用此蓝（定版设计钦点）。 */
private val VoiceBubbleBlue = Color(0xFF2F7DF6)

/** 波形条高度由转写文本哈希决定：同一条语音每次画出来一样，不同语音各不相同。 */
private fun voiceWaveHeights(transcript: String): List<Int> {
    val hash = transcript.hashCode()
    return List(18) { index ->
        val bits = (hash ushr ((index % 8) * 3)) and 0x7
        8 + bits * 2
    }
}

private fun formatVoiceDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

/** 输入框里的已选标签（设计图 07）：黑底白字，点一下清除该选择。 */
@Composable
internal fun InputTagChip(label: String, onClear: () -> Unit) {
    Surface(
        onClick = onClear,
        shape = RoundedCornerShape(13.dp),
        color = Ink,
        modifier = Modifier.semantics { contentDescription = "清除$label" }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = Bg, style = MaterialTheme.typography.labelMedium)
            Text("×", color = Bg, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 「＋」菜单里选专家/选技能用的单行选项：标题＋副标题，选中态打勾。 */
@Composable
internal fun PickerOptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Card2 else Card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, style = MaterialTheme.typography.bodyMedium)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (selected) Text("已选", color = Mint, style = MaterialTheme.typography.labelSmall)
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
                "Nestro",
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
