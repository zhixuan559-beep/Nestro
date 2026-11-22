package app.nestro.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nestro.NestroApplication
import app.nestro.data.GeneratedPluginDraftTransport
import app.nestro.data.GeneratedPluginFactory
import app.nestro.data.GeneratedPluginPackage
import app.nestro.data.GeneratedPluginPackageCodec
import app.nestro.data.GeneratedPluginPackageValidator
import app.nestro.data.GeneratedPluginStore
import app.nestro.data.AgentTaskStateEntity
import app.nestro.data.AgentTaskTraceEntity
import app.nestro.model.ModelConfig
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class GeneratedPluginAuditEntry(
    val state: AgentTaskStateEntity,
    val traces: List<AgentTaskTraceEntity>
)

@Composable
internal fun GeneratedPluginCenterPage(
    application: NestroApplication,
    configs: List<ModelConfig>,
    onUsage: (ModelConfig, Int, Int) -> Unit,
    onProviderUsage: (ModelConfig, Int, Int) -> Unit,
    onChanged: () -> Unit
) {
    val store = remember(application) { GeneratedPluginStore(application) }
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<GeneratedPluginPackage?>(null) }
    var updateTarget by remember { mutableStateOf<GeneratedPluginPackage?>(null) }
    var plugins by remember {
        mutableStateOf(run {
            store.list(application.agentToolCatalog)
            store.list()
        })
    }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("插件以声明式工作流安装，不执行任意代码。") }
    var pluginToExport by remember { mutableStateOf<GeneratedPluginPackage?>(null) }
    var auditEntries by remember { mutableStateOf<List<GeneratedPluginAuditEntry>>(emptyList()) }

    suspend fun reloadAudit() {
        auditEntries = withContext(Dispatchers.IO) {
            val dao = application.database.dao()
            dao.recentGeneratedPluginTaskStates(10)
                .map { state ->
                    GeneratedPluginAuditEntry(
                        state = state,
                        traces = dao.recentAgentTaskTraces(state.taskId, 20).reversed()
                    )
                }
        }
    }

    LaunchedEffect(application) { reloadAudit() }

    fun reload() {
        store.list(application.agentToolCatalog)
        plugins = store.list()
    }

    val pluginExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val selected = pluginToExport
        if (uri != null && selected != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val payload = GeneratedPluginPackageCodec.encode(listOf(selected.copy(enabled = false)))
                        application.contentResolver.openOutputStream(uri)?.use { output ->
                            OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writer.write(payload) }
                        } ?: error("无法写入插件包")
                    }
                }
                status = if (result.isSuccess) "插件包已导出。导入其他设备时仍需重新审核工具权限。" else
                    "插件导出失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                pluginToExport = null
            }
        } else {
            pluginToExport = null
        }
    }
    val pluginImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ByteArrayOutputStream()
                        application.contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(bytes.size() + count <= 1_000_000) { "插件包超过 1 MB 限制" }
                                bytes.write(buffer, 0, count)
                            }
                        } ?: error("无法读取插件包")
                        val decoded = GeneratedPluginPackageCodec.decode(bytes.toString(Charsets.UTF_8.name()))
                        require(decoded.size == 1) { "插件包必须且只能包含一个有效插件" }
                        val candidate = decoded.single().copy(enabled = false)
                        GeneratedPluginPackageValidator.validate(candidate, application.agentToolCatalog)
                        store.install(candidate, application.agentToolCatalog)
                        candidate
                    }
                }
                result.onSuccess { imported ->
                    application.refreshGeneratedPluginTools()
                    reload()
                    onChanged()
                    status = "插件包“${imported.name}”已导入并安装，默认关闭。请检查后再启用。"
                }.onFailure {
                    status = "插件导入失败：" + (it.message ?: "插件包无效或依赖工具不匹配")
                }
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("插件工坊", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { pluginImportLauncher.launch(arrayOf("application/json", "text/json")) }, enabled = !busy) {
                Text("导入插件")
            }
        }
        Text(
            "描述你想自动化的流程，主模型会从已注册能力中组合工作步骤。插件仅允许调用只读或外部写入工具；执行时由统一 AgentRuntime 调度。",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = request,
            onValueChange = { request = it.take(8_000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("描述要创建的插件") },
            minLines = 3
        )
        Button(
            onClick = {
                if (configs.isEmpty() || request.isBlank()) {
                    status = if (configs.isEmpty()) "请先在模型管理中配置可用模型。" else "先描述插件要完成的工作。"
                } else {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val factory = GeneratedPluginFactory(
                                    GeneratedPluginDraftTransport { selectedConfigs, history, systemPrompt, usage ->
                                        application.chatExecutionService.completeWithFallback(
                                            selectedConfigs, history, systemPrompt,
                                            onUsage = usage, onProviderUsage = onProviderUsage
                                        )
                                    }
                                )
                                factory.generate(
                                    request = request.trim(),
                                    existingPluginIds = plugins.map { it.id }.filterNot { it == updateTarget?.id }.toSet(),
                                    discovery = application.agentToolCatalog,
                                    allowExistingPluginId = updateTarget?.id,
                                    configs = configs,
                                    onUsage = onUsage
                                )
                            }
                        }
                        result.onSuccess {
                            draft = it
                            status = "插件草案已生成。检查工作步骤后再安装；安装后默认关闭。"
                        }.onFailure {
                            status = "生成失败：" + (it.message ?: "模型没有返回有效插件包")
                        }
                        busy = false
                    }
                }
            },
            enabled = !busy && request.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "生成中…" else "AI 生成插件草案") }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

        draft?.let { candidate ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("草案预览", style = MaterialTheme.typography.titleMedium)
                    Text("${candidate.name} · ${candidate.id} · v${candidate.version}")
                    Text(candidate.description, style = MaterialTheme.typography.bodySmall)
                    Text("输入参数：${candidate.inputs.joinToString { it.name + if (it.required) "（必填）" else "（可选）" }}")
                    candidate.steps.forEachIndexed { index, step ->
                        Text("${index + 1}. ${step.id} → ${step.toolId}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("安全边界：不执行任意代码；高风险设备、身份验证和金融工具不能加入插件。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val wasUpdate = updateTarget != null
                            val result = runCatching {
                                val target = updateTarget
                                if (target == null) store.install(candidate, application.agentToolCatalog)
                                else {
                                    require(candidate.id == target.id) { "升级草案必须保留原插件 ID" }
                                    store.replaceVersion(candidate, application.agentToolCatalog)
                                }
                            }
                            result.onSuccess {
                                draft = null
                                updateTarget = null
                                application.refreshGeneratedPluginTools()
                                reload()
                                onChanged()
                                status = if (wasUpdate) "插件版本已更新并默认停用，可检查后重新启用。" else "插件已安装，默认关闭。启用后才能在主聊天中调用。"
                            }.onFailure { status = it.message ?: "插件安装/更新失败" }
                        }) { Text(if (updateTarget == null) "安装（默认关闭）" else "更新版本（默认关闭）") }
                        OutlinedButton(onClick = { draft = null; updateTarget = null }) { Text("丢弃草案") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("执行审计", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { reloadAudit() } }, enabled = !busy) { Text("刷新记录") }
        }
        if (auditEntries.isEmpty()) {
            Text("暂无插件执行记录。插件运行后会记录工具调用、结果和失败原因。", style = MaterialTheme.typography.bodySmall)
        } else {
            auditEntries.forEach { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${entry.state.owner.removePrefix("generated-plugin:")} · ${entry.state.status}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(DateFormat.getDateTimeInstance().format(Date(entry.state.updatedAt)), style = MaterialTheme.typography.labelSmall)
                        if (entry.state.lastError.isNotBlank()) {
                            Text(entry.state.lastError.take(600), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        entry.traces.forEach { trace ->
                            Text(
                                "${trace.event}：${trace.detail.take(400)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        Text("已安装插件（${plugins.size}）", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(plugins, key = { it.id }) { plugin ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${plugin.name} · v${plugin.version}", style = MaterialTheme.typography.titleSmall)
                        Text(plugin.id, style = MaterialTheme.typography.labelSmall)
                        Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                        val compatibility = runCatching {
                            GeneratedPluginPackageValidator.validate(plugin, application.agentToolCatalog)
                        }
                        Text(
                            if (compatibility.isSuccess) "工具依赖匹配"
                            else "依赖不兼容：${compatibility.exceptionOrNull()?.message ?: "请重新审查插件"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (compatibility.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text("${plugin.steps.size} 个步骤 · ${plugin.inputs.size} 个输入参数", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val result = runCatching {
                                    store.setEnabled(plugin.id, !plugin.enabled, application.agentToolCatalog)
                                }
                                result.onSuccess {
                                    application.refreshGeneratedPluginTools()
                                    reload()
                                    onChanged()
                                    status = if (it.enabled) "插件已启用，可在主聊天中由模型按需调用。" else "插件已停用，已从工具目录移除。"
                                }.onFailure { status = it.message ?: "插件状态更新失败" }
                            }) { Text(if (plugin.enabled) "停用" else "启用") }
                            OutlinedButton(onClick = {
                                if (store.remove(plugin.id)) {
                                    application.refreshGeneratedPluginTools()
                                    reload()
                                    onChanged()
                                    status = "插件已卸载。"
                                }
                            }) { Text("卸载") }
                            if (store.historyVersions(plugin.id).isNotEmpty()) {
                                OutlinedButton(onClick = {
                                    val result = runCatching { store.rollbackPrevious(plugin.id, application.agentToolCatalog) }
                                    result.onSuccess {
                                        application.refreshGeneratedPluginTools()
                                        reload()
                                        onChanged()
                                        status = "已回滚到 ${it.version}，插件默认停用。"
                                    }.onFailure { status = it.message ?: "插件回滚失败" }
                                }) { Text("回滚上一版本") }
                            }
                            OutlinedButton(onClick = {
                                pluginToExport = plugin
                                pluginExportLauncher.launch("plugin-${plugin.id}.json")
                            }) { Text("导出") }
                            OutlinedButton(onClick = {
                                updateTarget = plugin
                                draft = null
                                request = "升级现有插件 ID：${plugin.id}。当前版本：${plugin.version}。保持相同 ID，生成更高的 SemVer 版本；在不改变既有输入含义的前提下优化以下流程：${plugin.description}"
                                status = "正在准备插件升级草案，请检查生成结果后再替换。"
                            }) { Text("生成升级草案") }
                        }
                    }
                }
            }
        }
    }
}
