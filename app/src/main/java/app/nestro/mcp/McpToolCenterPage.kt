package app.nestro.mcp

import app.nestro.NestroApplication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.UUID
import java.text.DateFormat
import java.util.Date

@Composable
fun McpToolCenterPage(app: NestroApplication) {
    val repository = remember(app) { app.mcpToolCenterRepository }
    val service = remember(app) { app.mcpToolCenterService }
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(repository.listServers()) }
    var tools by remember { mutableStateOf(repository.listTools()) }
    var audits by remember { mutableStateOf(repository.listAudits()) }
    var serverName by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var bearerToken by remember { mutableStateOf("") }
    var credentialDrafts by remember { mutableStateOf(emptyMap<String, String>()) }
    var status by remember { mutableStateOf("") }
    var toolSearch by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var selectedTool by remember { mutableStateOf<McpToolDescriptor?>(null) }
    var argumentsJson by remember { mutableStateOf("{}") }
    var deleteServer by remember { mutableStateOf<McpServerRecord?>(null) }

    fun reload() {
        servers = repository.listServers()
        tools = repository.listTools()
        audits = repository.listAudits()
    }
    fun discover(serverId: String) {
        scope.launch {
            loading = true
            status = "正在连接 MCP 服务并读取工具目录…"
            try {
                val found = service.discover(serverId)
                status = "发现完成，共 ${found.size} 个工具。已有授权状态已保留。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = "连接或发现失败：" + (error.message ?: "未知错误")
            } finally {
                reload()
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    val enabledServerIds = remember(servers) { servers.filter { it.enabled }.map { it.id }.toSet() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MCP 工具中心", style = MaterialTheme.typography.headlineSmall)
        Text(
            "连接支持 Streamable HTTP 的远程 MCP 服务。服务地址仅接受 HTTPS；不会在手机上启动或运行任意本地 MCP Server。工具发现不等于授权，必须逐个授权后才会进入任务工具目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("添加远程服务", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = serverName, onValueChange = { serverName = it },
            label = { Text("服务名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = endpoint, onValueChange = { endpoint = it },
            label = { Text("MCP Streamable HTTP HTTPS 地址") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = bearerToken, onValueChange = { bearerToken = it },
            label = { Text("Bearer Token（可选）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Button(
            enabled = !loading && serverName.isNotBlank() && endpoint.isNotBlank() &&
                McpEndpointPolicy.validate(endpoint) == null,
            onClick = {
                val id = UUID.randomUUID().toString()
                val name = serverName.trim()
                val url = endpoint.trim()
                val token = bearerToken.trim()
                scope.launch {
                    loading = true
                    try {
                        withContext(Dispatchers.IO) {
                            repository.saveServer(McpServerRecord(id, name, url))
                            if (token.isNotBlank()) repository.saveCredential(id, token)
                        }
                        serverName = ""; endpoint = ""; bearerToken = ""
                        status = "正在连接 MCP 服务并读取工具目录…"
                        val found = service.discover(id)
                        status = "发现完成，共 ${found.size} 个工具。"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        status = "保存或连接失败：" + (error.message ?: "未知错误")
                    } finally {
                        reload()
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存并发现工具") }

        if (endpoint.isNotBlank() && McpEndpointPolicy.validate(endpoint) != null) {
            Text(
                McpEndpointPolicy.validate(endpoint).orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        HorizontalDivider()
        Text("已连接服务（${servers.size}）", style = MaterialTheme.typography.titleMedium)
        if (servers.isEmpty()) {
            Text("还没有添加 MCP 服务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        servers.forEach { server ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall)
                            Text(server.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = server.enabled,
                            enabled = !loading,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    loading = true
                                    try {
                                        withContext(Dispatchers.IO) { service.setServerEnabled(server.id, enabled) }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        status = "服务状态更新失败：" + (error.message ?: "未知错误")
                                    } finally {
                                        loading = false
                                        reload()
                                    }
                                }
                            }
                        )
                    }
                    Text(
                        if (repository.hasCredential(server.id)) "Bearer Token：已加密保存" else "Bearer Token：未配置",
                        style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedTextField(
                        value = credentialDrafts[server.id].orEmpty(),
                        onValueChange = { credentialDrafts = credentialDrafts + (server.id to it) },
                        label = { Text("设置或更新 Bearer Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    TextButton(
                        enabled = !loading && credentialDrafts[server.id].orEmpty().isNotBlank(),
                        onClick = {
                            val token = credentialDrafts[server.id].orEmpty().trim()
                            scope.launch {
                                loading = true
                                try {
                                    withContext(Dispatchers.IO) { repository.saveCredential(server.id, token) }
                                    credentialDrafts = credentialDrafts - server.id
                                    status = "Bearer Token 已加密更新。"
                                    reload()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    status = "凭据更新失败：" + (error.message ?: "未知错误")
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    ) { Text("保存凭据") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !loading, onClick = { discover(server.id) }) { Text("刷新工具") }
                        if (repository.hasCredential(server.id)) {
                            TextButton(enabled = !loading, onClick = {
                                scope.launch {
                                    loading = true
                                    try {
                                        withContext(Dispatchers.IO) { repository.clearCredential(server.id) }
                                        status = "凭据已清除。"
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        status = "凭据清除失败：" + (error.message ?: "未知错误")
                                    } finally {
                                        loading = false
                                        reload()
                                    }
                                }
                            }) { Text("清除凭据") }
                        }
                        TextButton(enabled = !loading, onClick = { deleteServer = server }) {
                            Text("删除服务", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("已发现工具（${tools.size}）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = toolSearch,
            onValueChange = { toolSearch = it },
            label = { Text("搜索工具名称、说明或服务") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        val visibleTools = tools.filter {
            toolSearch.isBlank() || it.name.contains(toolSearch, true) ||
                it.description.contains(toolSearch, true) || it.serverName.contains(toolSearch, true)
        }
        if (tools.isEmpty()) {
            Text("连接服务并刷新后，工具会显示在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        visibleTools.forEach { tool ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                tool.serverName + " · " + if (tool.readOnlyHint) "服务声明只读" else "写入/未知权限",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tool.granted,
                            enabled = !loading && (tool.granted || tool.serverId in enabledServerIds),
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    loading = true
                                    try {
                                        withContext(Dispatchers.IO) { service.setToolGranted(tool.id, enabled) }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        status = "工具授权更新失败：" + (error.message ?: "未知错误")
                                    } finally {
                                        loading = false
                                        reload()
                                    }
                                }
                            }
                        )
                    }
                    Text(tool.description.ifBlank { "服务未提供工具说明" }, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (tool.granted) "已授权：任务可发现此工具，每次调用仍需确认" else "未授权：不会注册到任务工具目录",
                        style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedButton(
                        enabled = tool.granted && !loading,
                        onClick = { selectedTool = tool; argumentsJson = "{}" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("调用预览") }
                }
            }
        }
        HorizontalDivider()
        Text("最近调用记录（${audits.size}）", style = MaterialTheme.typography.titleMedium)
        if (audits.isEmpty()) {
            Text("MCP 工具调用记录会保存在本机，不记录调用参数或返回正文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        audits.take(20).forEach { audit ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(audit.serverName + " · " + audit.toolName, style = MaterialTheme.typography.bodySmall)
                    Text(DateFormat.getDateTimeInstance().format(Date(audit.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (audit.succeeded) "成功" else "失败", color = if (audit.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    selectedTool?.let { tool ->
        val validation = McpToolArgumentValidator.validate(tool.inputSchemaJson, argumentsJson)
        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text("调用前预览：${tool.name}") },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("服务：" + tool.serverName, style = MaterialTheme.typography.bodySmall)
                    Text(tool.description, style = MaterialTheme.typography.bodySmall)
                    Text("参数 Schema", style = MaterialTheme.typography.labelLarge)
                    Text(tool.inputSchemaJson.take(4_000), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = argumentsJson,
                        onValueChange = { argumentsJson = it },
                        label = { Text("本次调用参数 JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 10
                    )
                    if (validation != null) {
                        Text(validation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "每次调用都会展示参数预览；服务端返回内容视为不可信数据，不会自动获得其他工具或设备权限。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validation == null && !loading,
                    onClick = {
                        val id = tool.id
                        val args = argumentsJson
                        selectedTool = null
                        scope.launch {
                            loading = true
                            status = "正在调用 " + tool.name + "…"
                            try {
                                val value = service.invoke(id, args)
                                status = if (value.success) "调用成功：\n" + value.output else "调用失败：" + value.error
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                status = "调用失败：" + (error.message ?: "未知错误")
                            } finally {
                                loading = false
                                reload()
                            }
                        }
                    }
                ) { Text("确认并调用") }
            },
            dismissButton = { TextButton(onClick = { selectedTool = null }) { Text("取消") } }
        )
    }

    deleteServer?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteServer = null },
            title = { Text("删除 MCP 服务？") },
            text = {
                Text("删除“${server.name}”会同时删除其工具目录、授权状态和本地加密凭据。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = server.id
                    deleteServer = null
                    scope.launch {
                        loading = true
                        try {
                            withContext(Dispatchers.IO) { service.deleteServer(id) }
                            status = "服务及关联凭据已删除。"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            status = "删除失败：" + (error.message ?: "未知错误")
                        } finally {
                            loading = false
                            reload()
                        }
                    }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteServer = null }) { Text("取消") } }
        )
    }
}
