package app.nestro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import app.nestro.model.ModelComparisonCoordinator
import app.nestro.model.ModelConfigurationRepository
import app.nestro.model.ModelRegistry
import app.nestro.model.ModelDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ModelComparisonPage(
    registry: ModelRegistry,
    repository: ModelConfigurationRepository,
    reasoningEffort: String,
    onBack: () -> Unit,
    onManageModels: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val selectable = remember(registry) { registry.selectableModels().sortedBy { it.name.lowercase() } }
    var leftModelId by remember { mutableStateOf("") }
    var rightModelId by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var leftAnswer by remember { mutableStateOf("") }
    var rightAnswer by remember { mutableStateOf("") }
    var leftError by remember { mutableStateOf("") }
    var rightError by remember { mutableStateOf("") }
    var leftResultModelName by remember { mutableStateOf("") }
    var rightResultModelName by remember { mutableStateOf("") }
    var leftRunning by remember { mutableStateOf(false) }
    var rightRunning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var activeCoordinator by remember { mutableStateOf<ModelComparisonCoordinator?>(null) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var leftMenu by remember { mutableStateOf(false) }
    var rightMenu by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    LaunchedEffect(selectable) {
        if (selectable.none { it.id == leftModelId }) leftModelId = selectable.getOrNull(0)?.id.orEmpty()
        if (selectable.none { it.id == rightModelId } || rightModelId == leftModelId) {
            rightModelId = selectable.firstOrNull { it.id != leftModelId }?.id.orEmpty()
        }
    }

    DisposableEffect(activeCoordinator) {
        val coordinator = activeCoordinator
        onDispose { coordinator?.cancel() }
    }

    fun clearResults() {
        leftAnswer = ""; rightAnswer = ""; leftError = ""; rightError = ""
        leftResultModelName = ""; rightResultModelName = ""
        status = ""
    }

    fun cancelComparison() {
        activeCoordinator?.cancel()
        runJob?.cancel()
        status = "正在停止两路请求…"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回") }
            Column(Modifier.weight(1f)) {
                Text("双模型对比", color = Ink, style = MaterialTheme.typography.headlineSmall)
                Text("同一条指令并行发送给两个模型，直接比较回答质量", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (selectable.size < 2) {
            Card(colors = CardDefaults.cardColors(containerColor = Card2)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("至少需要两个可用模型", color = Ink, style = MaterialTheme.typography.titleMedium)
                    Text("请先在模型管理中配置两个已启用、拥有可用 API Key 的模型。", color = Muted)
                    TextButton(onClick = onManageModels) { Text("返回模型管理", color = Mint) }
                }
            }
        } else {
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModelChoice("模型 A", selectable, leftModelId, leftMenu, !leftRunning && !rightRunning, { leftMenu = it }, Modifier.weight(1f)) {
                        leftModelId = it
                        if (it == rightModelId) rightModelId = selectable.firstOrNull { model -> model.id != it }?.id.orEmpty()
                        clearResults()
                    }
                    ModelChoice("模型 B", selectable, rightModelId, rightMenu, !leftRunning && !rightRunning, { rightMenu = it }, Modifier.weight(1f)) {
                        rightModelId = it
                        if (it == leftModelId) leftModelId = selectable.firstOrNull { model -> model.id != it }?.id.orEmpty()
                        clearResults()
                    }
                }
            } else {
                ModelChoice("模型 A", selectable, leftModelId, leftMenu, !leftRunning && !rightRunning, { leftMenu = it }, Modifier.fillMaxWidth()) {
                    leftModelId = it
                    if (it == rightModelId) rightModelId = selectable.firstOrNull { model -> model.id != it }?.id.orEmpty()
                    clearResults()
                }
                ModelChoice("模型 B", selectable, rightModelId, rightMenu, !leftRunning && !rightRunning, { rightMenu = it }, Modifier.fillMaxWidth()) {
                    rightModelId = it
                    if (it == leftModelId) leftModelId = selectable.firstOrNull { model -> model.id != it }?.id.orEmpty()
                    clearResults()
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(12_000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入要对比的问题或任务") },
                placeholder = { Text("例如：分析这份商业计划的风险，并给出可执行建议") },
                minLines = 4,
                maxLines = 8,
                enabled = !leftRunning && !rightRunning
            )
            TextButton(onClick = { showSystemPrompt = !showSystemPrompt }) {
                Text(if (showSystemPrompt) "收起共同系统指令" else "添加共同系统指令", color = Mint)
            }
            if (showSystemPrompt) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it.take(8_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("共同系统指令（可选）") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !leftRunning && !rightRunning
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val left = leftModelId
                        val right = rightModelId
                        if (left.isBlank() || right.isBlank() || left == right || prompt.isBlank()) {
                            status = "请选择两个不同模型，并填写对比内容"
                        } else {
                            clearResults()
                            leftResultModelName = selectable.firstOrNull { it.id == left }?.name.orEmpty()
                            rightResultModelName = selectable.firstOrNull { it.id == right }?.name.orEmpty()
                            leftRunning = true
                            rightRunning = true
                            status = "两个模型正在并行生成"
                            val coordinator = ModelComparisonCoordinator(repository)
                            activeCoordinator = coordinator
                            runJob = scope.launch {
                                try {
                                    val results = coordinator.compare(
                                        registry = registry,
                                        firstModelId = left,
                                        secondModelId = right,
                                        prompt = prompt.trim(),
                                        systemPrompt = systemPrompt.trim(),
                                        reasoningEffort = reasoningEffort,
                                        onFirstChunk = { chunk -> leftAnswer += chunk },
                                        onSecondChunk = { chunk -> rightAnswer += chunk }
                                    )
                                    leftAnswer = results.first.response
                                    rightAnswer = results.second.response
                                    leftError = results.first.error.orEmpty()
                                    rightError = results.second.error.orEmpty()
                                    status = when {
                                        leftError.isBlank() && rightError.isBlank() -> "两路回答均已完成"
                                        leftError.isNotBlank() && rightError.isNotBlank() -> "两个模型均未能完成请求"
                                        else -> "其中一个模型失败，另一侧结果已保留"
                                    }
                                } catch (cancelled: CancellationException) {
                                    status = "已停止本次对比"
                                } catch (error: Throwable) {
                                    status = error.message?.take(300)?.ifBlank { "对比请求失败" } ?: "对比请求失败"
                                } finally {
                                    leftRunning = false
                                    rightRunning = false
                                    runJob = null
                                    activeCoordinator = null
                                }
                            }
                        }
                    },
                    enabled = !leftRunning && !rightRunning && prompt.isNotBlank() && leftModelId.isNotBlank() && rightModelId.isNotBlank() && leftModelId != rightModelId,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("开始并行对比")
                }
                if (leftRunning || rightRunning) {
                    OutlinedButton(onClick = { cancelComparison() }) {
                        Icon(Icons.Outlined.Stop, contentDescription = null)
                        Text("停止")
                    }
                }
                TextButton(onClick = {
                    leftAnswer = ""; rightAnswer = ""; leftError = ""; rightError = ""; status = ""
                }, enabled = !leftRunning && !rightRunning) { Text("清空") }
            }
            if (status.isNotBlank()) Text(status, color = Mint, style = MaterialTheme.typography.bodySmall)

            if (leftAnswer.isNotBlank() || rightAnswer.isNotBlank() || leftError.isNotBlank() || rightError.isNotBlank() || leftRunning || rightRunning) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        ComparisonResultCard("模型 A", leftResultModelName.ifBlank { selectable.firstOrNull { it.id == leftModelId }?.name ?: "模型 A" }, leftAnswer, leftError, leftRunning, { clipboard.setText(AnnotatedString(leftAnswer)) }, Modifier.weight(1f))
                        ComparisonResultCard("模型 B", rightResultModelName.ifBlank { selectable.firstOrNull { it.id == rightModelId }?.name ?: "模型 B" }, rightAnswer, rightError, rightRunning, { clipboard.setText(AnnotatedString(rightAnswer)) }, Modifier.weight(1f))
                    }
                } else {
                    ComparisonResultCard("模型 A", selectable.firstOrNull { it.id == leftModelId }?.name ?: "模型 A", leftAnswer, leftError, leftRunning, { clipboard.setText(AnnotatedString(leftAnswer)) }, Modifier.fillMaxWidth())
                    ComparisonResultCard("模型 B", selectable.firstOrNull { it.id == rightModelId }?.name ?: "模型 B", rightAnswer, rightError, rightRunning, { clipboard.setText(AnnotatedString(rightAnswer)) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ModelChoice(
    label: String,
    models: List<ModelDefinition>,
    selectedModelId: String,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
    onSelected: (String) -> Unit
) {
    val selectedName = models.firstOrNull { it.id == selectedModelId }?.name ?: "选择模型"
    Column(modifier) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { onExpandedChange(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(selectedName, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.name)
                                Text(model.modelCode, style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        },
                        onClick = { onSelected(model.id); onExpandedChange(false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparisonResultCard(
    label: String,
    modelName: String,
    answer: String,
    error: String,
    running: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Card2)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Mint, style = MaterialTheme.typography.labelMedium)
                    Text(modelName, color = Ink, style = MaterialTheme.typography.titleMedium)
                }
                if (answer.isNotBlank()) IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制回答", tint = Mint)
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (answer.isNotBlank()) Text(answer, color = Ink, style = MaterialTheme.typography.bodyMedium)
            if (answer.isBlank() && error.isBlank() && running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (answer.isBlank() && error.isBlank() && !running) Text("等待生成", color = Muted)
        }
    }
}
