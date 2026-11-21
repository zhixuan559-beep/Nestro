package app.nestro.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nestro.data.ArtifactRecord
import app.nestro.data.ArtifactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun ArtifactLibraryPage(projectId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var records by remember { mutableStateOf(emptyList<ArtifactRecord>()) }
    var selectedForExport by remember { mutableStateOf<ArtifactRecord?>(null) }
    var selectedForDelete by remember { mutableStateOf<ArtifactRecord?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var previewRecord by remember { mutableStateOf<ArtifactRecord?>(null) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var activeSaveRequestId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val record = selectedForExport
        if (uri != null && record != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ArtifactStore.export(context, record, uri) } }
                status = result.fold({ "已导出：${record.title} v${record.version}" }, { "导出失败：${it.message ?: "未知错误"}" })
            }
        }
        selectedForExport = null
    }

    LaunchedEffect(projectId) {
        title = ""
        content = ""
        status = ""
        saving = false
        activeSaveRequestId = null
        selectedForExport = null
        selectedForDelete = null
        preview = null
        previewRecord = null
    }

    LaunchedEffect(projectId, refresh) {
        records = emptyList()
        preview = null
        previewRecord = null
        val result = withContext(Dispatchers.IO) { runCatching { ArtifactStore.list(context, projectId) } }
        result.onSuccess { records = it }
            .onFailure { status = "加载产物失败：${it.message ?: "本机数据读取异常"}" }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("产物库", style = MaterialTheme.typography.headlineSmall)
        Text("助手回复可直接存入这里；也可以手动创建 Markdown 文稿。产物按项目隔离，保存在本机应用私有目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = title, onValueChange = { title = it.take(100) }, label = { Text("产物名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = content, onValueChange = { content = it.take(1_500_000) }, label = { Text("产物内容（Markdown）") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
        Button(
            enabled = !saving && title.isNotBlank() && content.isNotBlank(),
            onClick = {
                val draftTitle = title
                val draftContent = content
                val targetProjectId = projectId
                val requestId = UUID.randomUUID().toString()
                activeSaveRequestId = requestId
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ArtifactStore.save(context, targetProjectId, draftTitle, draftContent) }
                    }
                    if (activeSaveRequestId == requestId) {
                        activeSaveRequestId = null
                        saving = false
                        result.fold(
                            { saved ->
                                title = ""
                                content = ""
                                refresh++
                                status = "已保存到本机产物库：v${saved.version}"
                            },
                            { error -> status = "保存失败：${error.message ?: "未知错误"}" }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "正在保存…" else "保存产物") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已保存的产物", style = MaterialTheme.typography.titleMedium)
            Text("${records.size} 个版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("搜索作品名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        val visibleRecords = records.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
        if (visibleRecords.isEmpty()) {
            Text(
                if (records.isEmpty()) "这里还没有产物。" else "没有找到匹配的作品。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleRecords.forEach { record ->
                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(record.title, style = MaterialTheme.typography.titleSmall)
                            Text("v${record.version} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.createdAt))} · ${record.sizeBytes} bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val body = withContext(Dispatchers.IO) { runCatching { ArtifactStore.read(context, record) } }
                                        preview = body.getOrNull()?.let { record.title to it }
                                        previewRecord = record.takeIf { body.isSuccess }
                                        if (body.isFailure) status = "读取失败：${body.exceptionOrNull()?.message ?: "文件不可用"}"
                                    }
                                }) { Text("预览") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val body = withContext(Dispatchers.IO) { runCatching { ArtifactStore.read(context, record) } }
                                        body.onSuccess { text ->
                                            runCatching {
                                                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                                                    .putExtra(Intent.EXTRA_SUBJECT, record.title)
                                                    .putExtra(Intent.EXTRA_TEXT, text)
                                                context.startActivity(Intent.createChooser(intent, "分享产物"))
                                            }.onFailure { status = "分享失败：${it.message ?: "当前设备无法打开分享面板"}" }
                                        }.onFailure { status = "分享失败：${it.message ?: "未知错误"}" }
                                    }
                                }) { Text("分享") }
                                TextButton(onClick = {
                                    selectedForExport = record
                                    exportLauncher.launch(record.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70) + "-v${record.version}.md")
                                }) { Text("导出") }
                                TextButton(onClick = {
                                    scope.launch {
                                        selectedForDelete = record
                                    }
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedForDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { selectedForDelete = null },
            title = { Text("删除作品版本？") },
            text = { Text("将删除「${record.title}」v${record.version}。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    selectedForDelete = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { ArtifactStore.delete(context, record) }
                        }
                        result.onSuccess {
                            refresh++
                            status = "已删除：${record.title} v${record.version}"
                        }.onFailure {
                            status = "删除失败：${it.message ?: "本机存储异常"}"
                        }
                    }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { selectedForDelete = null }) { Text("取消") }
            }
        )
    }
    preview?.let { (name, body) ->
        AlertDialog(
            onDismissRequest = { preview = null; previewRecord = null },
            title = { Text(name) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (body.length > 12000) body.take(12000) + "\n\n……预览已截断，完整内容可导出或分享。" else body)
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = previewRecord != null, onClick = {
                        val source = previewRecord
                        if (source != null) scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { ArtifactStore.save(context, projectId, source.title, body) } }
                            result.onSuccess { saved ->
                                status = "已从历史版本另存为新版本：v${saved.version}"
                                refresh++
                                preview = null
                                previewRecord = null
                            }.onFailure { error ->
                                status = "恢复历史版本失败：${error.message ?: "本机存储异常"}"
                            }
                        }
                    }) { Text("恢复为新版本") }
                    TextButton(onClick = { preview = null; previewRecord = null }) { Text("关闭") }
                }
            }
        )
    }
}
