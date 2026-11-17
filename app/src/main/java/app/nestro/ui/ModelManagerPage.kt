package app.nestro.ui

import app.nestro.data.ModelConfig
import app.nestro.data.ModelProfileMetadata
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelUsageStats
import app.nestro.model.ModelRegistry
import app.nestro.model.ModelProvider
import app.nestro.model.ModelApiKey
import app.nestro.model.ModelDefinition
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
    registry: ModelRegistry,
    activeRegistryModelId: String,
    loadApiKey: (String) -> String,
    onSaveProvider: (ModelProvider) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSaveApiKey: (ModelApiKey, String) -> Unit,
    onSaveRegistryModel: (ModelDefinition) -> Unit,
    onDeleteRegistryApiKey: (String) -> Unit,
    onDeleteRegistryModel: (String) -> Unit,
    onActivateRegistryModel: (String) -> Unit,
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
    var providerNameDraft by remember { mutableStateOf("") }
    var providerUrlDraft by remember { mutableStateOf("https://") }
    var editingProviderId by remember { mutableStateOf("") }
    var confirmDeleteProvider by remember { mutableStateOf(false) }
    var selectedProviderId by remember {
        mutableStateOf(registry.providers.firstOrNull()?.id.orEmpty())
    }
    var apiKeyLabelDraft by remember { mutableStateOf("") }
    var apiKeyDraft by remember { mutableStateOf("") }
    var modelNameDraft by remember { mutableStateOf("") }
    var modelCodeDraft by remember { mutableStateOf("") }
    var selectedModelKeyIds by remember { mutableStateOf(emptySet<String>()) }
    var replacingApiKeyId by remember { mutableStateOf("") }
    var registryMessage by remember { mutableStateOf("") }

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
        Text("新版模型目录 · 服务商 / API Key / 模型独立管理", color = Ink, style = MaterialTheme.typography.titleMedium)
        Text("新版目录与旧版配置并存兼容。密钥只进入加密存储，目录中仅保存 Key 标识。", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (registryMessage.isNotBlank()) Text(registryMessage, color = Mint, style = MaterialTheme.typography.bodySmall)

        Text("服务商", color = Ink, style = MaterialTheme.typography.titleSmall)
        registry.providers.forEach { provider ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedProviderId == provider.id, onClick = {
                    selectedProviderId = provider.id
                    replacingApiKeyId = ""
                    apiKeyLabelDraft = ""
                    apiKeyDraft = ""
                    selectedModelKeyIds = emptySet()
                })
                Column(Modifier.weight(1f)) {
                    Text(provider.name, color = Ink)
                    Text(provider.baseUrl, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    selectedProviderId = provider.id
                    editingProviderId = provider.id
                    providerNameDraft = provider.name
                    providerUrlDraft = provider.baseUrl
                }) { Text("编辑", color = Mint) }
                TextButton(onClick = { onSaveProvider(provider.copy(enabled = !provider.enabled)) }) {
                    Text(if (provider.enabled) "停用" else "启用", color = if (provider.enabled) Mint else ColorStop)
                }
                TextButton(onClick = { confirmDeleteProvider = true }) { Text("删除", color = ColorStop) }
            }
        }
        OutlinedTextField(providerNameDraft, { providerNameDraft = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("新增服务商名称") }, singleLine = true)
        OutlinedTextField(providerUrlDraft, { providerUrlDraft = it.take(2048) }, modifier = Modifier.fillMaxWidth(), label = { Text("服务商 API 根地址") }, singleLine = true)
        OutlinedButton(
            onClick = {
                val url = providerUrlDraft.trim().trimEnd('/')
                if (providerNameDraft.isBlank() || !url.startsWith("https://", true)) {
                    registryMessage = "请填写服务商名称和 HTTPS 地址"
                } else {
                    val provider = ModelProvider(
                        editingProviderId.ifBlank { UUID.randomUUID().toString() },
                        providerNameDraft.trim(),
                        url
                    )
                    onSaveProvider(provider)
                    selectedProviderId = provider.id
                    editingProviderId = ""
                    providerNameDraft = ""
                    providerUrlDraft = "https://"
                    registryMessage = "服务商已保存"
                }
            },
            enabled = providerNameDraft.isNotBlank() && providerUrlDraft.startsWith("https://"),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (editingProviderId.isBlank()) "添加服务商" else "保存服务商修改") }
        if (editingProviderId.isNotBlank()) {
            TextButton(onClick = {
                editingProviderId = ""
                providerNameDraft = ""
                providerUrlDraft = "https://"
            }) { Text("取消编辑服务商") }
        }
        if (confirmDeleteProvider) {
            AlertDialog(
                onDismissRequest = { confirmDeleteProvider = false },
                title = { Text("删除服务商？") },
                text = { Text("将同时删除该服务商下的模型和 API Key。此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteProvider(selectedProviderId)
                        confirmDeleteProvider = false
                        selectedProviderId = registry.providers.firstOrNull { it.id != selectedProviderId }?.id.orEmpty()
                        editingProviderId = ""
                        providerNameDraft = ""
                        providerUrlDraft = "https://"
                        registryMessage = "服务商及其关联配置已删除"
                    }) { Text("确认删除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteProvider = false }) { Text("取消") }
                }
            )
        }

        if (selectedProviderId.isNotBlank()) {
            Text("API Key", color = Ink, style = MaterialTheme.typography.titleSmall)
            registry.apiKeys.filter { it.providerId == selectedProviderId }.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, color = Ink)
                        Text(if (loadApiKey(item.id).isNotBlank()) "已加密保存 · 可用" else "密钥缺失", color = if (loadApiKey(item.id).isNotBlank()) Mint else ColorStop, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { onSaveApiKey(item.copy(enabled = !item.enabled), loadApiKey(item.id)) },
                        enabled = loadApiKey(item.id).isNotBlank()
                    ) { Text(if (item.enabled) "停用" else "启用", color = if (item.enabled) Mint else ColorStop) }
                    TextButton(onClick = {
                        replacingApiKeyId = item.id
                        apiKeyLabelDraft = item.label
                        apiKeyDraft = ""
                    }) { Text("替换密钥", color = Mint) }
                    TextButton(onClick = {
                        onDeleteRegistryApiKey(item.id)
                        selectedModelKeyIds = selectedModelKeyIds - item.id
                        if (replacingApiKeyId == item.id) replacingApiKeyId = ""
                    }) { Text("删除", color = ColorStop) }
                }
            }
            OutlinedTextField(apiKeyLabelDraft, { apiKeyLabelDraft = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("Key 名称") }, singleLine = true)
            OutlinedTextField(apiKeyDraft, { apiKeyDraft = it.take(4096) }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key 密钥") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            OutlinedButton(
                onClick = {
                    val item = ModelApiKey(
                        replacingApiKeyId.ifBlank { UUID.randomUUID().toString() },
                        selectedProviderId,
                        apiKeyLabelDraft.trim()
                    )
                    onSaveApiKey(item, apiKeyDraft.trim())
                    replacingApiKeyId = ""
                    apiKeyLabelDraft = ""
                    apiKeyDraft = ""
                    registryMessage = "API Key 已加密保存"
                },
                enabled = apiKeyLabelDraft.isNotBlank() && apiKeyDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (replacingApiKeyId.isBlank()) "添加 API Key" else "保存替换后的 API Key") }

            Text("模型", color = Ink, style = MaterialTheme.typography.titleSmall)
            registry.models.filter { it.providerId == selectedProviderId }.forEach { item ->
                val available = item in registry.selectableModels()
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = if (item.id == activeRegistryModelId) Mint else Ink)
                            Text(item.modelCode, color = Muted, style = MaterialTheme.typography.bodySmall)
                            Text(if (available) "可用" else "缺少可用 Key 或已停用", color = if (available) Mint else ColorStop, style = MaterialTheme.typography.labelSmall)
                        }
                        if (item.id == activeRegistryModelId) Text("当前", color = Mint)
                    }
                    Row {
                        TextButton(onClick = { onSaveRegistryModel(item.copy(enabled = !item.enabled)) }) {
                            Text(if (item.enabled) "停用" else "启用", color = if (item.enabled) Mint else ColorStop)
                        }
                        TextButton(onClick = { onActivateRegistryModel(item.id) }, enabled = available && item.id != activeRegistryModelId) { Text("设为当前", color = Mint) }
                        TextButton(onClick = { onDeleteRegistryModel(item.id) }) { Text("删除模型", color = ColorStop) }
                    }
                }
            }
            OutlinedTextField(modelNameDraft, { modelNameDraft = it.take(80) }, modifier = Modifier.fillMaxWidth(), label = { Text("模型显示名称") }, singleLine = true)
            OutlinedTextField(modelCodeDraft, { modelCodeDraft = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("模型 ID / model code") }, singleLine = true)
            Text("关联 API Key（可多选，按顺序故障切换）", color = Muted, style = MaterialTheme.typography.bodySmall)
            registry.apiKeys.filter { it.providerId == selectedProviderId }.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.id in selectedModelKeyIds, onCheckedChange = { checked ->
                        selectedModelKeyIds = if (checked) selectedModelKeyIds + item.id else selectedModelKeyIds - item.id
                    })
                    Text(item.label, color = Ink)
                }
            }
            OutlinedButton(
                onClick = {
                    val model = ModelDefinition(
                        id = UUID.randomUUID().toString(),
                        providerId = selectedProviderId,
                        name = modelNameDraft.trim(),
                        modelCode = modelCodeDraft.trim(),
                        apiKeyIds = registry.apiKeys.filter { it.providerId == selectedProviderId && it.id in selectedModelKeyIds }.map { it.id }
                    )
                    onSaveRegistryModel(model)
                    modelNameDraft = ""
                    modelCodeDraft = ""
                    selectedModelKeyIds = emptySet()
                    registryMessage = "模型已保存"
                },
                enabled = modelNameDraft.isNotBlank() && modelCodeDraft.isNotBlank() &&
                    registry.apiKeys.any { it.providerId == selectedProviderId && it.id in selectedModelKeyIds && loadApiKey(it.id).isNotBlank() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加模型") }
        }
        HorizontalDivider(color = Line)
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
