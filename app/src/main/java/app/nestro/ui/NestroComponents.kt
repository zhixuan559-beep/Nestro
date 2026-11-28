package app.nestro.ui

import app.nestro.R
import app.nestro.data.ChatMessage
import app.nestro.data.ExpertProfile
import app.nestro.data.VoiceMessageMarker
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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

/** 设计图 02 的白色圆钮（顶栏圆钮同款：白底＋细边＋轻阴影）。 */
@Composable
private fun WhiteRoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = Bg,
        border = BorderStroke(1.dp, Line),
        shadowElevation = 2.dp
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** 专家/主助手头像：有吉祥物（卡皮巴拉）显示吉祥物图，否则显示头像 Emoji 或名字首字。 */
@Composable
internal fun MascotAvatar(mascot: String, avatar: String, name: String, size: Dp) {
    val mark = if (avatar.isNotBlank() && avatar != "✦") avatar else name.firstOrNull()?.toString() ?: "✦"
    Box(
        Modifier.size(size).background(Card2, RoundedCornerShape(32)).border(1.dp, Line, RoundedCornerShape(32)),
        contentAlignment = Alignment.Center
    ) {
        if (mascot == "capybara") {
            Image(
                painterResource(R.drawable.mascot_capybara),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(32)),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(mark, color = Ink, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 专家团图标（改版第 b 步）：成员吉祥物组合——几个小人同框挤在一个小图标里。
 * 2×2 迷你格：每个成员一个小圆（有吉祥物显示吉祥物图，否则显示名字首字）。
 */
@Composable
internal fun TeamMascotIcon(members: List<String>, profiles: Map<String, ExpertProfile>, size: Dp = 40.dp) {
    val shown = members.take(4)
    val mini = 19.dp
    Box(
        Modifier.size(size).background(Card2, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            shown.chunked(2).forEach { rowMembers ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    rowMembers.forEach { member ->
                        val profile = profiles[member]
                        val mark = member.firstOrNull()?.toString() ?: "✦"
                        Box(
                            Modifier.size(mini).background(Bg, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (profile?.mascot == "capybara") {
                                Image(
                                    painterResource(R.drawable.mascot_capybara),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(mark, color = Ink, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 抽屉里的一行会话入口：头像/图标＋名字，点进该会话。 */
@Composable
private fun DrawerChatRow(avatar: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        avatar()
        Text(label, color = Ink, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 9.dp)
    )
}

/**
 * 改版第 b 步：抽屉照设计图 02 重排——顶部搜索＋「＋」圆钮，「＋」只有创建专家、创建群聊两项；
 * 列表 = 主助手＋专家/专家团/群聊（各自独立会话，点行进会话）；底部连接入口。
 * 抽屉里列出全部专家（含内置三位：各有独立会话，藏起来反而找不到）；主助手固定置顶。
 */
@Composable
internal fun NavigationDrawerContent(
    modifier: Modifier = Modifier,
    assistantName: String,
    experts: List<String>,
    expertProfiles: Map<String, ExpertProfile>,
    expertTeams: List<ExpertTeam>,
    onOpenMainChat: () -> Unit,
    onOpenExpertChat: (String) -> Unit,
    onOpenTeamChat: (String, String) -> Unit,
    onCreateExpert: () -> Unit,
    onCreateTeam: () -> Unit,
    onNavigate: (String) -> Unit,
    onClose: (() -> Unit)? = null
) {
    var showPlusMenu by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val query = searchQuery.trim()
    val shownExperts = experts.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    val shownTeams = expertTeams.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    Column(
        modifier.background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WhiteRoundButton(Icons.Outlined.Search, "搜索") {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
            }
            Box {
                WhiteRoundButton(Icons.Outlined.Add, "新建") { showPlusMenu = true }
                DropdownMenu(expanded = showPlusMenu, onDismissRequest = { showPlusMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("创建专家") },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, tint = Ink) },
                        onClick = { showPlusMenu = false; onCreateExpert() }
                    )
                    DropdownMenuItem(
                        text = { Text("创建群聊") },
                        leadingIcon = { Icon(Icons.Outlined.Group, contentDescription = null, tint = Ink) },
                        onClick = { showPlusMenu = false; onCreateTeam() }
                    )
                }
            }
        }
        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索专家与群聊") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            onClick = onOpenMainChat,
            color = Card2,
            shape = RoundedCornerShape(15.dp),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "主助手对话" }
        ) {
            Row(
                Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MascotAvatar(mascot = "capybara", avatar = "✦", name = assistantName, size = 40.dp)
                Text(
                    "$assistantName（主助手）",
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
        DrawerSectionTitle("我的专家")
        shownExperts.forEach { name ->
            val profile = expertProfiles[name]
            DrawerChatRow(
                avatar = { MascotAvatar(mascot = profile?.mascot ?: "capybara", avatar = profile?.avatar ?: "✦", name = name, size = 40.dp) },
                label = name,
                onClick = { onOpenExpertChat(name) }
            )
        }
        shownTeams.forEach { team ->
            DrawerChatRow(
                avatar = { TeamMascotIcon(members = team.members, profiles = expertProfiles) },
                label = team.name,
                onClick = { onOpenTeamChat(team.id, team.name) }
            )
        }
        if (query.isNotBlank() && shownExperts.isEmpty() && shownTeams.isEmpty()) {
            Text("没有匹配的专家或群聊", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
        DrawerSectionTitle("连接")
        DrawerChatRow(
            avatar = {
                Box(Modifier.size(40.dp).background(Card2, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Link, contentDescription = null, tint = Ink, modifier = Modifier.size(19.dp))
                }
            },
            label = "连接你的应用",
            onClick = { onNavigate("tools") }
        )
        DrawerChatRow(
            avatar = {
                Box(Modifier.size(40.dp).background(Card2, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Call, contentDescription = null, tint = Ink, modifier = Modifier.size(19.dp))
                }
            },
            label = "通讯应用",
            onClick = { onNavigate("phone-agent") }
        )
        // 改版第 c 步：过渡组已撤——最近对话/项目空间/设置改走资料页（点顶栏名字胶囊进），
        // 抽屉回到设计图 02 的样子：搜索＋「＋」、主助手、我的专家、连接。
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
