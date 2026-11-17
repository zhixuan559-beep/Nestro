package app.nestro.ui

import app.nestro.data.ModelConfig
import app.nestro.data.ChatMessage
import app.nestro.data.ModelProfileMetadata
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelUsageStats
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun ModelManager(
    profiles: List<ModelProfileMetadata>,
    activeProfileId: String,
    selectableProfileIds: Set<String>,
    activeConfig: ModelConfig,
    usage: Map<String, ModelUsageStats>,
    failoverEnabled: Boolean,
    onFailoverChange: (Boolean) -> Unit,
    loadKey: (String) -> String,
    onActivate: (String) -> Unit,
    onSave: (ModelProfileMetadata, String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteKey: (String) -> Unit,
    onTestConnection: suspend (ModelConfig) -> String
) {
    val scope = rememberCoroutineScope()
    var editingId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var revealKey by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var activateAfterSave by remember { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }

    fun beginNew() {
        editingId = java.util.UUID.randomUUID().toString()
        name = ""
        endpoint = "https://"
        model = ""
        key = ""
        revealKey = false
        activateAfterSave = true
        testStatus = ""
        formError = ""
    }
    fun edit(profile: ModelProfileMetadata) {
        editingId = profile.id
        name = profile.name
        endpoint = profile.baseUrl
        model = profile.model
        key = loadKey(profile.id)
        revealKey = false
        activateAfterSave = profile.id == activeProfileId
        testStatus = ""
        formError = ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型管理", color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text("最多保存 20 组模型配置，支持不同服务商与模型切换。所有配置共用 OpenAI Chat Completions 兼容协议。", color = Muted)
        Surface(color = Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动故障切换", color = Ink)
                    Text("主模型连接失败时，按配置列表顺序尝试其他已保存 Key。开启后可能产生备用服务商费用。", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = failoverEnabled, onCheckedChange = onFailoverChange)
            }
        }
        if (profiles.isEmpty()) {
            Text("还没有模型配置。先添加一组 API 地址、模型名称和 Key。", color = Muted)
        } else {
            profiles.forEach { profile ->
                val active = profile.id == activeProfileId
                Surface(color = if (active) Card2 else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name + if (active) " · 当前使用" else "", color = if (active) Mint else Ink, style = MaterialTheme.typography.titleMedium)
                                Text("${profile.model} · ${profile.baseUrl}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text(if (loadKey(profile.id).isNotBlank()) "API Key 已加密保存 · 可用" else "API Key 已删除或未配置 · 此模型不可选择", color = if (loadKey(profile.id).isNotBlank()) Mint else ColorStop, style = MaterialTheme.typography.labelSmall)
                                usage[profile.id]?.let { stats ->
                                    Text("成功请求 ${stats.requests} 次 · 估算 Token：输入 ${stats.estimatedInputTokens} / 输出 ${stats.estimatedOutputTokens}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { edit(profile) }) { Text("编辑", color = Mint) }
                            TextButton(onClick = { onActivate(profile.id) }, enabled = !active && profile.id in selectableProfileIds && loadKey(profile.id).isNotBlank()) { Text("启用", color = Mint) }
                            TextButton(onClick = { onDeleteKey(profile.id); if (editingId == profile.id) { key = ""; revealKey = false } }, enabled = loadKey(profile.id).isNotBlank()) { Text("删除 Key", color = ColorStop) }
                            TextButton(onClick = { onDelete(profile.id); if (editingId == profile.id) beginNew() }) { Text("删除模型", color = ColorStop) }
                        }
                    }
                }
            }
        }
        Text(if (editingId in profiles.map { it.id }) "编辑模型配置" else "新增模型配置", color = Ink, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it.take(40) }, modifier = Modifier.fillMaxWidth(), label = { Text("配置名称") }, singleLine = true)
        OutlinedTextField(endpoint, { endpoint = it.take(2048) }, modifier = Modifier.fillMaxWidth(), label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true)
        OutlinedTextField(model, { model = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称") }, placeholder = { Text("例如 deepseek-chat") }, singleLine = true)
        OutlinedTextField(key, { key = it.take(4096) }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = if (revealKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, trailingIcon = { TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "隐藏" else "查看") } })
        Text("每组 Key 独立使用 Android Keystore AES-GCM 加密。备份只包含配置名称、地址和模型名，不包含 Key。", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = activateAfterSave, onCheckedChange = { activateAfterSave = it })
            Text("保存后立即设为当前模型", color = Ink)
        }
        Button(
            onClick = {
                val candidate = ModelProfileMetadata(editingId, name.trim(), endpoint.trim().trimEnd('/'), model.trim())
                formError = ModelProfileCatalogCodec.validate(candidate)
                    ?: ModelProfileCatalogCodec.validateCatalog(profiles.filterNot { it.id == editingId } + candidate).orEmpty()
                if (formError.isBlank() && key.isBlank() && editingId !in profiles.map { it.id }) formError = "新配置必须填写 API Key"
                if (formError.isBlank()) onSave(candidate, key.trim(), activateAfterSave)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
        ) { Text("保存模型配置") }
        OutlinedButton(
            onClick = {
                val candidate = ModelConfig(endpoint.trim().trimEnd('/'), model.trim(), key.trim())
                testing = true
                testStatus = "正在测试连接…"
                scope.launch {
                    testStatus = runCatching { onTestConnection(candidate) }
                        .fold({ it }, { "连接失败：${it.message ?: "请检查地址、Key 与网络"}" })
                    testing = false
                }
            },
            enabled = !testing && endpoint.startsWith("https://") && model.isNotBlank() && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "测试中…" else "测试当前编辑配置") }
        if (formError.isNotBlank()) Text(formError, color = ColorStop, style = MaterialTheme.typography.bodySmall)
        if (testStatus.isNotBlank()) Text(testStatus, color = if (testStatus.startsWith("连接失败")) ColorStop else Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { beginNew() }, modifier = Modifier.fillMaxWidth()) { Text("＋ 新建另一组配置", color = Mint) }
        Text("当前实际调用的模型：${activeConfig.model.ifBlank { "未配置" }}", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
