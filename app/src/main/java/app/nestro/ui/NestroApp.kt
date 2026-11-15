package app.nestro.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Bg = androidx.compose.ui.graphics.Color(0xFF0F1115)
private val Card = androidx.compose.ui.graphics.Color(0xFF1A1C20)
private val Card2 = androidx.compose.ui.graphics.Color(0xFF232529)
private val Mint = androidx.compose.ui.graphics.Color(0xFF34D399)
private val Ink = androidx.compose.ui.graphics.Color(0xFFE8EAED)
private val Muted = androidx.compose.ui.graphics.Color(0xFF8A8F98)
private val Line = androidx.compose.ui.graphics.Color(0xFF2A2D33)

private data class ChatMessage(val id: String, val role: String, val content: String)
private data class ModelConfig(val baseUrl: String, val model: String, val apiKey: String)

@Composable
fun NestroApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE) }
    var page by remember { mutableStateOf("home") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(ModelConfig("", "", "")) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未配置模型") }
    var experts by remember { mutableStateOf(listOf("文案官", "选题狐", "代码佬")) }
    var expertPrompts by remember { mutableStateOf(loadExpertPrompts(prefs)) }
    var skills by remember { mutableStateOf(loadSkills(prefs)) }
    var selectedSkill by remember { mutableStateOf("") }
    var skillDraftName by remember { mutableStateOf("") }
    var skillDraftPrompt by remember { mutableStateOf("") }
    var showSkillDialog by remember { mutableStateOf(false) }
    var selectedExpert by remember { mutableStateOf("通用助手") }
    var expertDraft by remember { mutableStateOf("") }
    var expertPromptDraft by remember { mutableStateOf("") }
    var showExpertDialog by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("备份不包含 API Key，恢复后仍需在本机配置模型。") }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            backupStatus = runCatching {
                val json = buildLocalBackup(prefs)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法创建备份文件")
                "备份已导出"
            }.getOrElse { "导出失败：${it.message ?: "未知错误"}" }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            backupStatus = runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("无法读取文件")
                require(raw.length <= 10_000_000) { "备份文件过大" }
                restoreLocalBackup(prefs, raw)
                messages = loadMessages(prefs)
                experts = loadExperts(prefs)
                expertPrompts = loadExpertPrompts(prefs)
                skills = loadSkills(prefs)
                "恢复完成。模型 Key 未被覆盖，请检查模型管理。"
            }.getOrElse { "恢复失败：${it.message ?: "文件格式无效"}" }
        }
    }

    LaunchedEffect(Unit) {
        config = LocalVault.read(context)
        messages = loadMessages(prefs)
        experts = loadExperts(prefs)
        expertPrompts = loadExpertPrompts(prefs)
        status = if (config.apiKey.isNotBlank() && config.model.isNotBlank()) "模型已就绪" else "先连接你自己的模型"
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Card, primary = Mint, onPrimary = Bg,
            onBackground = Ink, onSurface = Ink, secondary = Muted
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Row(Modifier.fillMaxSize()) {
                if (showDrawer) {
                    Column(
                        Modifier.width(286.dp).fillMaxHeight().background(Card).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Nestro", style = MaterialTheme.typography.headlineSmall, color = Mint)
                        Text("你的本机 AI 工作站", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        DrawerItem("⌂", "工作台") { page = "home"; showDrawer = false }
                        DrawerItem("▦", "项目空间") { page = "spaces"; showDrawer = false }
                        DrawerItem("✦", "我的专家") { page = "experts"; showDrawer = false }
                        DrawerItem("◇", "能力中心") { page = "skills"; showDrawer = false }
                        DrawerItem("⚙", "模型管理") { page = "models"; showDrawer = false }
                        DrawerItem("▤", "数据与设置") { page = "settings"; showDrawer = false }
                        Spacer(Modifier.weight(1f))
                        Text("数据保存在本机", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showDrawer = !showDrawer }) {
                                Icon(Icons.Outlined.Menu, contentDescription = "菜单", tint = Ink)
                            }
                            Column {
                                Text("Nestro", color = Ink, style = MaterialTheme.typography.titleLarge)
                                Text(status, color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { page = "models" }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "模型设置", tint = Mint)
                        }
                    }

                    when (page) {
                        "home", "chat" -> {
                            if (messages.isEmpty()) {
                                Column(
                                    Modifier.weight(1f).fillMaxWidth().padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        Modifier.size(76.dp).background(Mint, RoundedCornerShape(24.dp)),
                                        contentAlignment = Alignment.Center
                                    ) { Text("✦", color = Bg, style = MaterialTheme.typography.headlineLarge) }
                                    Spacer(Modifier.height(20.dp))
                                    Text("今天想创造点什么？", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.height(8.dp))
                                    Text("连接自己的模型，组合专家、技能与知识", color = Muted)
                                    Spacer(Modifier.height(24.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Suggestion("写作创作") { draft = "帮我完成一项写作任务" }
                                        Suggestion("分析资料") { draft = "帮我分析这份资料" }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Suggestion("开发代码") { draft = "帮我规划并编写代码" }
                                        Suggestion("创建专家") { showExpertDialog = true }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(messages, key = { it.id }) { msg ->
                                        MessageBubble(msg)
                                    }
                                    if (busy) item { Text("正在思考…", color = Mint, modifier = Modifier.padding(10.dp)) }
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(onClick = { page = "models" }, label = { Text(config.model.ifBlank { "配置模型" }) })
                                    AssistChip(onClick = { page = "experts" }, label = { Text(selectedExpert) })
                                    AssistChip(onClick = { page = "skills" }, label = { Text(selectedSkill.ifBlank { "选择技能" }) })
                                }
                                OutlinedTextField(
                                    value = draft, onValueChange = { draft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("问点什么…", color = Muted) },
                                    trailingIcon = {
                                        IconButton(
                                            enabled = draft.isNotBlank() && !busy,
                                            onClick = {
                                                val prompt = draft.trim()
                                                draft = ""
                                                val user = ChatMessage(UUID.randomUUID().toString(), "user", prompt)
                                                messages = messages + user
                                                saveMessages(prefs, messages)
                                                if (config.apiKey.isBlank() || config.model.isBlank()) {
                                                    status = "请先配置模型 API"
                                                    messages = messages + ChatMessage(
                                                        UUID.randomUUID().toString(), "assistant",
                                                        "还没有配置模型。进入「模型管理」，填写兼容 OpenAI Chat Completions 的 API 地址、模型名称和 Key。"
                                                    )
                                                    saveMessages(prefs, messages)
                                                } else {
                                                    busy = true
                                                    scope.launch {
                                                        val answer = withContext(Dispatchers.IO) {
                                                            runCatching { OpenAiClient.complete(config, messages, listOf(expertPrompts[selectedExpert].orEmpty(), skills[selectedSkill].orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")) }
                                                        }
                                                        messages = messages + ChatMessage(
                                                            UUID.randomUUID().toString(), "assistant",
                                                            answer.getOrElse { "请求失败：${it.message ?: "请检查网络、地址和 Key"}" }
                                                        )
                                                        saveMessages(prefs, messages)
                                                        status = if (answer.isSuccess) "已连接 · 本地保存" else "连接失败"
                                                        busy = false
                                                    }
                                                }
                                            }
                                        ) { Icon(Icons.Outlined.Send, contentDescription = "发送", tint = Mint) }
                                    },
                                    shape = RoundedCornerShape(22.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Mint, unfocusedBorderColor = Line,
                                        focusedTextColor = Ink, unfocusedTextColor = Ink,
                                        cursorColor = Mint
                                    )
                                )
                                Text("对话记录保存在本机；API Key 使用 Android Keystore 加密。", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 7.dp))
                            }
                        }
                        "models" -> ModelManager(config, onSave = { newConfig ->
                            config = newConfig
                            LocalVault.write(context, newConfig)
                            status = if (newConfig.apiKey.isNotBlank()) "模型配置已保存" else "尚未配置模型"
                            page = "home"
                        })
                        "experts" -> {
                            Column(Modifier.weight(1f).padding(18.dp)) {
                                Text("我的专家", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("专家配置保存在本机。选择专家后可用于组织会话。", color = Muted)
                                Spacer(Modifier.height(14.dp))
                                experts.forEach { name ->
                                    Surface(
                                        color = if (selectedExpert == name) Card2 else Card,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedExpert = name; page = "home" }
                                    ) { Text("✦  $name", color = if (selectedExpert == name) Mint else Ink, modifier = Modifier.padding(16.dp)) }
                                }
                                Button(onClick = { showExpertDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("＋ 创建专家")
                                }
                            }
                        }
                        "settings" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("数据与设置", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("本地备份可迁移会话与专家配置。API Key 由当前设备的 Android Keystore 保护，不会写入备份。", color = Muted)
                                Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("本地数据备份", color = Ink, style = MaterialTheme.typography.titleMedium)
                                        Text("包含最近 200 条对话、专家名称和专家提示词。导出文件可自行保存到安全位置。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = {
                                            val name = "Nestro-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
                                            exportBackupLauncher.launch(name)
                                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                            Text("导出本地备份")
                                        }
                                        OutlinedButton(onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("从备份恢复")
                                        }
                                        Text(backupStatus, color = if (backupStatus.startsWith("恢复失败") || backupStatus.startsWith("导出失败")) androidx.compose.ui.graphics.Color(0xFFF87171) else Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("隐私与安全", color = Ink, style = MaterialTheme.typography.titleMedium)
                                        Text("会话与专家数据保存在应用私有存储。API Key 使用 Android Keystore 的 AES-GCM 加密。备份不包含 Key，也不会上传到 Nestro 服务器。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        "spaces" -> SimplePage("项目空间", "项目空间隔离与独立知识库尚未接入。当前版本不会把普通会话伪装成已隔离空间。")
                        "skills" -> {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("本地技能库", color = Ink, style = MaterialTheme.typography.headlineSmall)
                                Text("技能是可复用的工作指令。选择后会与当前专家提示词组合，仅影响新发送的请求。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                skills.forEach { (name, prompt) ->
                                    Surface(color = if (selectedSkill == name) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f).clickable { selectedSkill = if (selectedSkill == name) "" else name; page = "home" }) {
                                                Text(name, color = if (selectedSkill == name) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                                Text(prompt.take(100), color = Muted, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (name !in DEFAULT_SKILLS.keys) {
                                                TextButton(onClick = {
                                                    skills = skills - name
                                                    prefs.edit().putString("skills", skillMapToJson(skills)).apply()
                                                    if (selectedSkill == name) selectedSkill = ""
                                                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFF87171)) }
                                            }
                                        }
                                    }
                                }
                                Button(onClick = { showSkillDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                                    Text("＋ 创建本地技能")
                                }
                                OutlinedButton(onClick = { selectedSkill = ""; page = "home" }, modifier = Modifier.fillMaxWidth()) { Text("本轮不使用技能") }
                                Text("外部 Skill / MCP 仓库导入仍未开放。导入功能上线前不会执行第三方代码。", color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        else -> SimplePage("Nestro", "本地优先的开放式 AI 工作站")
                    }
                }
            }
        }
    }

    if (showSkillDialog) {
        AlertDialog(
            onDismissRequest = { showSkillDialog = false },
            title = { Text("创建本地技能") },
            text = {
                Column {
                    OutlinedTextField(value = skillDraftName, onValueChange = { skillDraftName = it }, label = { Text("技能名称") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = skillDraftPrompt, onValueChange = { skillDraftPrompt = it }, label = { Text("工作指令") }, placeholder = { Text("描述技能要如何处理任务、输出格式和注意事项。") }, minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = skillDraftName.trim()
                    val prompt = skillDraftPrompt.trim()
                    if (name.isNotBlank() && prompt.isNotBlank() && name.length <= 40 && prompt.length <= 8000) {
                        skills = skills + (name to prompt)
                        prefs.edit().putString("skills", skillMapToJson(skills)).apply()
                        selectedSkill = name
                    }
                    skillDraftName = ""
                    skillDraftPrompt = ""
                    showSkillDialog = false
                }) { Text("保存", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showSkillDialog = false }) { Text("取消") } }
        )
    }

    if (showExpertDialog) {
        AlertDialog(
            onDismissRequest = { showExpertDialog = false },
            title = { Text("创建专家") },
            text = {
                Column {
                    OutlinedTextField(value = expertDraft, onValueChange = { expertDraft = it }, label = { Text("专家名称") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = expertPromptDraft, onValueChange = { expertPromptDraft = it }, label = { Text("专家角色与工作要求") }, placeholder = { Text("例如：你是一位严谨的中文文案编辑，先判断目标读者，再给出可直接发布的文案。") }, minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = expertDraft.trim()
                    if (name.isNotEmpty() && name !in experts) {
                        experts = experts + name
                        expertPrompts = expertPrompts + (name to expertPromptDraft.trim())
                        prefs.edit().putString("experts", JSONArray(experts).toString())
                            .putString("expert_prompts", expertPromptsToJson(expertPrompts)).apply()
                        selectedExpert = name
                    }
                    expertDraft = ""
                    expertPromptDraft = ""
                    showExpertDialog = false
                    page = "experts"
                }) { Text("创建", color = Mint) }
            },
            dismissButton = { TextButton(onClick = { showExpertDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun DrawerItem(icon: String, label: String, onClick: () -> Unit) {
    Surface(color = Card2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text("$icon   $label", color = Ink, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Card, shape = RoundedCornerShape(50)) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ink)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) androidx.compose.ui.graphics.Color(0xFF173B30) else Card,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(message.content, color = Ink, modifier = Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ModelManager(config: ModelConfig, onSave: (ModelConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }
    var endpoint by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var key by remember { mutableStateOf(config.apiKey) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型管理", color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text("支持 OpenAI Chat Completions 兼容接口。服务商可自行选择。", color = Muted)
        OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true)
        OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称") }, placeholder = { Text("例如 deepseek-chat") }, singleLine = true)
        OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        Text("Key 只保存在本机加密存储，不会写入仓库。", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSave(ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), key.trim())) },
            enabled = endpoint.startsWith("https://") && model.isNotBlank() && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
        ) { Text("保存模型配置") }
        OutlinedButton(
            onClick = {
                testing = true
                testStatus = "正在测试连接…"
                val candidate = ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), key.trim())
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { OpenAiClient.complete(candidate, listOf(ChatMessage(UUID.randomUUID().toString(), "user", "Reply with OK.")), "") }
                    }
                    testStatus = result.fold({ "连接成功：模型已返回内容。" }, { "连接失败：${it.message ?: "请检查地址、Key 与网络"}" })
                    testing = false
                }
            },
            enabled = !testing && endpoint.startsWith("https://") && model.isNotBlank() && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "测试中…" else "测试连接") }
        if (testStatus.isNotBlank()) Text(testStatus, color = if (testStatus.startsWith("连接失败")) androidx.compose.ui.graphics.Color(0xFFF87171) else Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onSave(ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), "")) },
            enabled = config.apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("清除本机 API Key") }
    }
}

@Composable
private fun SimplePage(title: String, description: String) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

private object OpenAiClient {
    fun complete(config: ModelConfig, history: List<ChatMessage>, systemPrompt: String): String {
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    history.takeLast(40).forEach { msg ->
                        put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                })
                put("stream", false)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { !it.isNullOrBlank() } ?: "HTTP $code")
            }
            val root = JSONObject(response)
            return root.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            connection.disconnect()
        }
    }
}

private object LocalVault {
    private const val ALIAS = "nestro_api_key_v1"
    fun write(context: Context, config: ModelConfig) {
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(config.apiKey.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString("base_url", config.baseUrl).putString("model", config.model)
            .putString("api_key_enc", packed).apply()
    }
    fun read(context: Context): ModelConfig {
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val packed = prefs.getString("api_key_enc", null)
        val key = runCatching {
            if (packed.isNullOrBlank()) "" else {
                val parts = packed.split(":")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        return ModelConfig(prefs.getString("base_url", "") ?: "", prefs.getString("model", "") ?: "", key)
    }
    private fun getKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}

private fun loadMessages(prefs: android.content.SharedPreferences): List<ChatMessage> = runCatching {
    val array = JSONArray(prefs.getString("messages", "[]"))
    (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        ChatMessage(item.getString("id"), item.getString("role"), item.getString("content"))
    }
}.getOrDefault(emptyList())

private fun saveMessages(prefs: android.content.SharedPreferences, messages: List<ChatMessage>) {
    val array = JSONArray()
    messages.takeLast(200).forEach { msg ->
        array.put(JSONObject().put("id", msg.id).put("role", msg.role).put("content", msg.content))
    }
    prefs.edit().putString("messages", array.toString()).apply()
}

private fun loadExperts(prefs: android.content.SharedPreferences): List<String> = runCatching {
    val array = JSONArray(prefs.getString("experts", """["文案官","选题狐","代码佬"]"""))
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(listOf("文案官", "选题狐", "代码佬"))


private fun loadExpertPrompts(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val raw = prefs.getString("expert_prompts", "{}") ?: "{}"
    val json = JSONObject(raw)
    buildMap { val keys = json.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, json.optString(key)) } }
}.getOrDefault(emptyMap())


private fun expertPromptsToJson(prompts: Map<String, String>): String {
    val json = JSONObject()
    prompts.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}

private fun buildLocalBackup(prefs: android.content.SharedPreferences): String {
    val root = JSONObject()
    root.put("format", "nestro-local-backup")
    root.put("version", 1)
    root.put("created_at", System.currentTimeMillis())
    root.put("messages", prefs.getString("messages", "[]") ?: "[]")
    root.put("experts", prefs.getString("experts", """["文案官","选题狐","代码佬"]""") ?: "[]")
    root.put("expert_prompts", prefs.getString("expert_prompts", "{}") ?: "{}")
    return root.toString(2)
}

private fun restoreLocalBackup(prefs: android.content.SharedPreferences, raw: String) {
    val root = JSONObject(raw)
    require(root.optString("format") == "nestro-local-backup") { "不是 Nestro 备份文件" }
    require(root.optInt("version") == 1) { "暂不支持此备份版本" }
    val messages = JSONArray(root.getString("messages"))
    require(messages.length() <= 200) { "对话记录数量超出限制" }
    for (i in 0 until messages.length()) {
        val item = messages.getJSONObject(i)
        require(item.optString("role") in setOf("user", "assistant")) { "消息角色无效" }
        require(item.has("content") && item.has("id")) { "消息字段缺失" }
    }
    val experts = JSONArray(root.getString("experts"))
    require(experts.length() <= 100) { "专家数量超出限制" }
    for (i in 0 until experts.length()) require(experts.getString(i).length <= 80) { "专家名称过长" }
    val prompts = JSONObject(root.getString("expert_prompts"))
    require(prompts.length() <= 100) { "专家提示词数量超出限制" }
    val cleanPrompts = JSONObject()
    val keys = prompts.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        val prompt = prompts.optString(name)
        require(prompt.length <= 20000) { "专家提示词过长" }
        cleanPrompts.put(name, prompt)
    }
    prefs.edit()
        .putString("messages", messages.toString())
        .putString("experts", experts.toString())
        .putString("expert_prompts", cleanPrompts.toString())
        .apply()
}


private val DEFAULT_SKILLS = linkedMapOf(
    "结构化总结" to "请提炼核心结论、关键事实、行动项与未解决问题。区分原文事实和你的推断，避免遗漏重要限制。",
    "精简改写" to "请在不改变原意的前提下压缩冗余表达。保留关键事实与语气，优先给出可直接使用的成稿。",
    "代码审查" to "请从正确性、安全性、边界条件、性能和可维护性审查代码。先指出确定的问题，再给最小修复建议，不要臆测未提供的上下文。"
)

private fun loadSkills(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val json = JSONObject(prefs.getString("skills", "{}") ?: "{}")
    val loaded = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        loaded[name] = json.optString(name)
    }
    DEFAULT_SKILLS + loaded
}.getOrDefault(DEFAULT_SKILLS)

private fun skillMapToJson(skills: Map<String, String>): String {
    val json = JSONObject()
    skills.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}
