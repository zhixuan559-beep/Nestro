package app.nestro.ui

import app.nestro.BuildConfig
import app.nestro.data.ModelConfig
import app.nestro.data.ModelProfileMetadata
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelUsageStats
import app.nestro.model.ModelRegistry
import app.nestro.model.ModelProvider
import app.nestro.model.ModelApiKey
import app.nestro.model.ModelDefinition
import app.nestro.model.ModelProviderPresetCatalog
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
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
    onTestConnection: suspend (ModelConfig) -> String,
    onDiscoverModels: suspend (ModelProvider, String) -> List<String>
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
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
    var providerToDeleteId by remember { mutableStateOf("") }
    var selectedProviderId by remember {
        mutableStateOf(registry.providers.firstOrNull()?.id.orEmpty())
    }
    var apiKeyLabelDraft by remember { mutableStateOf("") }
    var apiKeyDraft by remember { mutableStateOf("") }
    var modelNameDraft by remember { mutableStateOf("") }
    var modelCodeDraft by remember { mutableStateOf("") }
    var editingRegistryModelId by remember { mutableStateOf("") }
    var modelInputPriceDraft by remember { mutableStateOf("") }
    var modelOutputPriceDraft by remember { mutableStateOf("") }
    var modelCurrencyDraft by remember { mutableStateOf("USD") }
    var supportsReasoningEffortDraft by remember { mutableStateOf(false) }
    var selectedModelKeyIds by remember { mutableStateOf(emptySet<String>()) }
    var replacingApiKeyId by remember { mutableStateOf("") }
    var registryMessage by remember { mutableStateOf("") }
    var discoveringModels by remember { mutableStateOf(false) }
    var discoveredModelCodes by remember { mutableStateOf(emptyList<String>()) }
    var discoveryStatus by remember { mutableStateOf("") }

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
        Text("支持官方服务商预置、自定义服务商、多 Key、多模型，以及 OpenAI 兼容、Claude 原生和 Gemini 原生模型目录发现与请求。", color = Muted)
        Text("新版模型目录 · 服务商 / API Key / 模型独立管理", color = Ink, style = MaterialTheme.typography.titleMedium)
        Text("新版目录与旧版配置并存兼容。密钥只进入加密存储，目录中仅保存 Key 标识。", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (registryMessage.isNotBlank()) Text(registryMessage, color = Mint, style = MaterialTheme.typography.bodySmall)

        Text("官方服务商快捷预置", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text("选择后自动填入官方 API 地址和协议类型；你只需添加自己的 API Key。模型目录仍以服务商实际返回为准。", color = Muted, style = MaterialTheme.typography.bodySmall)
        ModelProviderPresetCatalog.all.forEach { preset ->
            val existingPreset = registry.providers.firstOrNull { it.presetId == preset.id }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.displayName, color = Ink)
                    Text(preset.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val provider = existingPreset ?: ModelProvider(
                            id = "preset-provider-${preset.id}",
                            name = preset.displayName,
                            baseUrl = preset.baseUrl,
                            presetId = preset.id,
                            protocol = preset.protocol
                        )
                        if (existingPreset == null) onSaveProvider(provider)
                        selectedProviderId = provider.id
                        replacingApiKeyId = ""
                        apiKeyLabelDraft = ""
                        apiKeyDraft = ""
                        registryMessage = if (existingPreset == null) {
                            "${preset.displayName} 已添加预置；在下方粘贴 API Key 后即可获取模型列表。"
                        } else {
                            "已选中 ${preset.displayName}；在下方管理密钥和模型。"
                        }
                    }) { Text(if (existingPreset == null) "添加" else "选择") }
                    if (preset.accountUrl != null) {
                        TextButton(onClick = { uriHandler.openUri(preset.accountUrl) }) {
                            Text(if (preset.id == "modelscope") "获取令牌" else "获取 API Key", color = Mint)
                        }
                    }
                }
            }
        }

        ModelProviderPresetCatalog.byId("modelscope")?.let {
            OutlinedButton(
                onClick = { uriHandler.openUri("https://modelscope.cn/magicube/usage") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("查看魔搭当前免费额度与魔粒") }
            Text("魔搭免费额度、可用模型和并发限制由账户实时决定；部分 API-Inference 服务可能要求绑定阿里云账号并完成实名认证。Nestro 不承诺永久免费或固定调用次数。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("魔搭官方 OAuth 仍需登记 Nestro 应用，并由安全服务端完成授权码换 Token；当前通过官方令牌页直达，不会收集账号密码。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    val backend = BuildConfig.MODELSCOPE_OAUTH_BACKEND_URL.trim().trimEnd('/')
                    if (!backend.startsWith("https://")) {
                        registryMessage = "魔搭一键授权服务尚未配置；部署 OAuth 服务后重新构建即可启用。"
                    } else {
                        val appState = UUID.randomUUID().toString().replace("-", "")
                        context.getSharedPreferences("nestro_local", android.content.Context.MODE_PRIVATE)
                            .edit().putString("modelscope_oauth_app_state", appState).apply()
                        uriHandler.openUri("$backend/oauth/modelscope/start?app_state=$appState")
                    }
                },
                enabled = BuildConfig.MODELSCOPE_OAUTH_BACKEND_URL.startsWith("https://"),
                modifier = Modifier.fillMaxWidth()
            ) { Text("连接魔搭账号（OAuth）") }
            Text(
                if (BuildConfig.MODELSCOPE_OAUTH_BACKEND_URL.startsWith("https://"))
                    "登录并授权后自动返回 Nestro，完成账号绑定和密钥保存。"
                else "待部署 OAuth 服务并通过 Gradle 属性 modelscopeOAuthBackendUrl 配置服务地址。",
                color = Muted, style = MaterialTheme.typography.bodySmall
            )
        }
        ModelProviderPresetCatalog.byId("siliconflow")?.let {
            Text("硅基流动使用 OpenAI 兼容协议；添加 API Key 后可直接刷新实时模型目录。模型价格、可用状态与速率限制以硅基流动平台为准。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        Text("服务商", color = Ink, style = MaterialTheme.typography.titleSmall)
        registry.providers.forEach { provider ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedProviderId == provider.id, onClick = {
                    selectedProviderId = provider.id
                    replacingApiKeyId = ""
                    apiKeyLabelDraft = ""
                    apiKeyDraft = ""
                    selectedModelKeyIds = emptySet()
                    editingRegistryModelId = ""
                    modelNameDraft = ""
                    modelCodeDraft = ""
                    modelInputPriceDraft = ""
                    modelOutputPriceDraft = ""
                    modelCurrencyDraft = "USD"
                    supportsReasoningEffortDraft = false
                })
                Column(Modifier.weight(1f)) {
                    Text(provider.name, color = Ink)
                    Text(
                        provider.baseUrl + " · " + when (provider.protocol) {
                            app.nestro.model.ModelApiProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI 兼容"
                            app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
                            app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT -> "Gemini 原生"
                            app.nestro.model.ModelApiProtocol.CUSTOM -> "自定义协议"
                        },
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!provider.id.startsWith("legacy-provider-")) {
                    TextButton(onClick = {
                        selectedProviderId = provider.id
                        editingProviderId = provider.id
                        providerNameDraft = provider.name
                        providerUrlDraft = provider.baseUrl
                    }) { Text("编辑", color = Mint) }
                }
                TextButton(onClick = { onSaveProvider(provider.copy(enabled = !provider.enabled)) }) {
                    Text(if (provider.enabled) "停用" else "启用", color = if (provider.enabled) Mint else ColorStop)
                }
                TextButton(onClick = {
                    providerToDeleteId = provider.id
                    confirmDeleteProvider = true
                }) { Text("删除", color = ColorStop) }
            }
        }
        OutlinedTextField(providerNameDraft, { providerNameDraft = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("新增服务商名称") }, singleLine = true)
        OutlinedTextField(
            providerUrlDraft,
            { providerUrlDraft = it.take(2048) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (editingProviderId.startsWith("legacy-provider-")) "旧配置服务商地址请在下方旧模型配置中修改" else "服务商 API 根地址") },
            enabled = !editingProviderId.startsWith("legacy-provider-"),
            singleLine = true
        )
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
                        val deletingId = providerToDeleteId
                        if (deletingId.isNotBlank()) onDeleteProvider(deletingId)
                        confirmDeleteProvider = false
                        providerToDeleteId = ""
                        if (selectedProviderId == deletingId) {
                            selectedProviderId = registry.providers.firstOrNull { it.id != deletingId }?.id.orEmpty()
                            editingRegistryModelId = ""
                            modelNameDraft = ""
                            modelCodeDraft = ""
                            selectedModelKeyIds = emptySet()
                            modelInputPriceDraft = ""
                            modelOutputPriceDraft = ""
                            modelCurrencyDraft = "USD"
                        }
                        if (editingProviderId == deletingId) {
                            editingProviderId = ""
                            providerNameDraft = ""
                            providerUrlDraft = "https://"
                        }
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
                },
                enabled = apiKeyLabelDraft.isNotBlank() && apiKeyDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (replacingApiKeyId.isBlank()) "添加 API Key" else "保存替换后的 API Key") }

            val selectedProvider = registry.providers.firstOrNull { it.id == selectedProviderId }
            OutlinedButton(
                onClick = {
                    val provider = selectedProvider
                    val apiKey = registry.apiKeys.firstOrNull { it.providerId == selectedProviderId && it.enabled }
                        ?.let { loadApiKey(it.id) }.orEmpty()
                    if (provider == null || apiKey.isBlank()) {
                        discoveryStatus = "请先为该服务商添加并启用 API Key。"
                    } else {
                        discoveringModels = true
                        discoveryStatus = "正在从服务商获取模型目录…"
                        scope.launch {
                            runCatching { onDiscoverModels(provider, apiKey) }
                                .onSuccess { models ->
                                    discoveredModelCodes = models
                                    discoveryStatus = if (models.isEmpty()) "服务商没有返回可用模型。" else "已获取 ${models.size} 个模型，选择后可填入模型 ID。"
                                }
                                .onFailure { discoveryStatus = "获取失败：" + (it.message ?: "未知错误") }
                            discoveringModels = false
                        }
                    }
                },
                enabled = !discoveringModels && selectedProvider != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (discoveringModels) "正在获取模型…" else "自动获取模型列表") }
            if (discoveryStatus.isNotBlank()) Text(discoveryStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
            discoveredModelCodes.forEach { discovered ->
                TextButton(onClick = {
                    modelCodeDraft = discovered
                    if (modelNameDraft.isBlank()) modelNameDraft = discovered
                }) { Text("使用模型：$discovered", color = Mint) }
            }

            Text("模型", color = Ink, style = MaterialTheme.typography.titleSmall)
            registry.models.filter { it.providerId == selectedProviderId }.forEach { item ->
                val providerProtocol = registry.providers.firstOrNull { it.id == item.providerId }?.protocol
                val protocolReady = providerProtocol == app.nestro.model.ModelApiProtocol.OPENAI_CHAT_COMPLETIONS ||
                    providerProtocol == app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES ||
                    providerProtocol == app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT
                val available = item in registry.selectableModels() && protocolReady
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = if (item.id == activeRegistryModelId) Mint else Ink)
                            Text(item.modelCode, color = Muted, style = MaterialTheme.typography.bodySmall)
                            Text(
                                when {
                                    !protocolReady -> "该协议暂不可启用"
                                    available -> "可用"
                                    else -> "缺少可用 Key 或已停用"
                                },
                                color = if (available) Mint else ColorStop,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (item.id == activeRegistryModelId) Text("当前", color = Mint)
                    }
                    Row {
                        TextButton(onClick = { onSaveRegistryModel(item.copy(enabled = !item.enabled)) }) {
                            Text(if (item.enabled) "停用" else "启用", color = if (item.enabled) Mint else ColorStop)
                        }
                        TextButton(onClick = {
                            editingRegistryModelId = item.id
                            modelNameDraft = item.name
                            modelCodeDraft = item.modelCode
                            selectedModelKeyIds = item.apiKeyIds.toSet()
                            modelInputPriceDraft = item.inputPricePerMillion?.toString().orEmpty()
                            modelOutputPriceDraft = item.outputPricePerMillion?.toString().orEmpty()
                            modelCurrencyDraft = item.currency
                            supportsReasoningEffortDraft = item.supportsReasoningEffort
                        }) { Text("编辑", color = Mint) }
                        TextButton(onClick = { onActivateRegistryModel(item.id) }, enabled = available && item.id != activeRegistryModelId) { Text("设为当前", color = Mint) }
                        TextButton(onClick = { onDeleteRegistryModel(item.id) }) { Text("删除模型", color = ColorStop) }
                    }
                }
            }
            OutlinedTextField(modelNameDraft, { modelNameDraft = it.take(80) }, modifier = Modifier.fillMaxWidth(), label = { Text("模型显示名称") }, singleLine = true)
            OutlinedTextField(modelCodeDraft, { modelCodeDraft = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("模型 ID / model code") }, singleLine = true)
            val selectedPresetId = registry.providers.firstOrNull { it.id == selectedProviderId }?.presetId.orEmpty()
            val suggestedModels = ModelProviderPresetCatalog.byId(selectedPresetId)?.suggestedModels.orEmpty()
            if (suggestedModels.isNotEmpty()) {
                Text("常用模型快捷选择（以服务商账户实际可用列表为准）", color = Muted, style = MaterialTheme.typography.bodySmall)
                suggestedModels.forEach { suggested ->
                    TextButton(onClick = {
                        modelCodeDraft = suggested
                        if (modelNameDraft.isBlank()) modelNameDraft = suggested
                    }) { Text(suggested, color = Mint) }
                }
            }
            OutlinedTextField(modelInputPriceDraft, { modelInputPriceDraft = it.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("输入价格 / 百万 Token（可留空）") }, singleLine = true)
            OutlinedTextField(modelOutputPriceDraft, { modelOutputPriceDraft = it.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("输出价格 / 百万 Token（可留空）") }, singleLine = true)
            OutlinedTextField(modelCurrencyDraft, { modelCurrencyDraft = it.take(3).uppercase() }, modifier = Modifier.fillMaxWidth(), label = { Text("价格币种（3 位代码）") }, singleLine = true)
            Text("编辑时会保留模型启停状态及原有价格等目录元数据；API Key 关联按当前勾选保存。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = supportsReasoningEffortDraft, onCheckedChange = { supportsReasoningEffortDraft = it })
                Text("该模型明确支持 reasoning_effort（四档参数）", color = Ink, style = MaterialTheme.typography.bodySmall)
            }
            Text("只有服务商确认支持 Off/Low/Medium/High 四档时才勾选；否则不发送该参数。", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                    val inputPrice = modelInputPriceDraft.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    val outputPrice = modelOutputPriceDraft.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    val existing = registry.models.firstOrNull { it.id == editingRegistryModelId }
                    val model = ModelDefinition(
                        id = editingRegistryModelId.ifBlank { UUID.randomUUID().toString() },
                        providerId = selectedProviderId,
                        name = modelNameDraft.trim(),
                        modelCode = modelCodeDraft.trim(),
                        apiKeyIds = registry.apiKeys.filter { it.providerId == selectedProviderId && it.id in selectedModelKeyIds }.map { it.id },
                        enabled = existing?.enabled ?: true,
                        inputPricePerMillion = inputPrice,
                        outputPricePerMillion = outputPrice,
                        currency = modelCurrencyDraft.trim().uppercase(),
                        supportsReasoningEffort = supportsReasoningEffortDraft
                    )
                    onSaveRegistryModel(model)
                    editingRegistryModelId = ""
                    modelNameDraft = ""
                    modelCodeDraft = ""
                    selectedModelKeyIds = emptySet()
                    modelInputPriceDraft = ""
                    modelOutputPriceDraft = ""
                    modelCurrencyDraft = "USD"
                },
                enabled = modelNameDraft.isNotBlank() && modelCodeDraft.isNotBlank() &&
                    (modelInputPriceDraft.isBlank() || modelInputPriceDraft.toDoubleOrNull()?.let { it >= 0.0 && it.isFinite() } == true) &&
                    (modelOutputPriceDraft.isBlank() || modelOutputPriceDraft.toDoubleOrNull()?.let { it >= 0.0 && it.isFinite() } == true) &&
                    modelCurrencyDraft.trim().length == 3 &&
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
                                    Text("成功请求 ${stats.requests} 次 · 服务商实报 Token：输入 ${stats.providerInputTokens} / 输出 ${stats.providerOutputTokens}（${stats.providerUsageRequests} 次）", color = Muted, style = MaterialTheme.typography.labelSmall)
                                    Text("本地估算 Token：输入 ${stats.estimatedInputTokens} / 输出 ${stats.estimatedOutputTokens}（${stats.estimatedUsageRequests} 次）", color = Muted, style = MaterialTheme.typography.labelSmall)
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
