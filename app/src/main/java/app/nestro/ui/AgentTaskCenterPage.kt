package app.nestro.ui

import app.nestro.NestroApplication
import app.nestro.data.AgentTaskStateEntity
import app.nestro.data.AgentTaskTraceEntity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AgentTaskCenterPage(
    application: NestroApplication,
    projectId: String
) {
    val scope = rememberCoroutineScope()
    var tasks by remember(projectId) { mutableStateOf(emptyList<AgentTaskStateEntity>()) }
    var traces by remember { mutableStateOf(emptyList<AgentTaskTraceEntity>()) }
    var expandedTaskId by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busyTaskId by remember { mutableStateOf("") }

    suspend fun reloadTasks() {
        tasks = withContext(Dispatchers.IO) {
            application.database.dao().recentAgentTaskStatesForProject(projectId, 200)
        }
        if (expandedTaskId.isNotBlank() && tasks.none { it.taskId == expandedTaskId }) {
            expandedTaskId = ""
            traces = emptyList()
        }
    }

    suspend fun loadTraces(taskId: String) {
        traces = withContext(Dispatchers.IO) {
            application.database.dao().recentAgentTaskTraces(taskId, 50).asReversed()
        }
    }

    LaunchedEffect(application, projectId, expandedTaskId) {
        reloadTasks()
        while (isActive) {
            delay(5_000)
            reloadTasks()
            if (expandedTaskId.isNotBlank()) loadTraces(expandedTaskId)
        }
    }

    fun performTaskAction(task: AgentTaskStateEntity, action: String) {
        if (busyTaskId.isNotBlank()) return
        busyTaskId = task.taskId
        scope.launch {
            val failure = try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "pause" -> application.agentTaskCoordinator.pause(task.taskId)
                        "resume" -> checkNotNull(application.agentTaskCoordinator.resume(task.taskId)) { "任务不存在" }
                        "cancel" -> application.agentTaskCoordinator.cancel(task.taskId)
                        else -> error("未知任务操作")
                    }
                }
                null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            status = if (failure == null) {
                when (action) {
                    "pause" -> "已提交暂停请求"
                    "resume" -> "已提交恢复请求"
                    else -> "已提交取消请求"
                }
            } else {
                "操作失败：${failure.message ?: "未知错误"}"
            }
            busyTaskId = ""
            reloadTasks()
            if (expandedTaskId == task.taskId) loadTraces(task.taskId)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("统一任务", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "当前项目内已接入统一任务机的跨模块任务状态与执行记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = { scope.launch { reloadTasks() } }) { Text("刷新") }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tasks.isEmpty()) {
            Text(
                "当前项目暂无统一任务记录。",
                modifier = Modifier.padding(vertical = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.taskId }) { task ->
                    val expanded = expandedTaskId == task.taskId
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        if (expanded) {
                                            expandedTaskId = ""
                                            traces = emptyList()
                                        } else {
                                            expandedTaskId = task.taskId
                                            loadTraces(task.taskId)
                                        }
                                    }
                                }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                task.title.ifBlank { task.kind.ifBlank { task.owner } },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "${taskStatusLabel(task.status)} · ${task.kind.ifBlank { task.owner }} · ${task.updatedAt.toTaskTime()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (task.resultSummary.isNotBlank()) {
                                Text(task.resultSummary.take(500), style = MaterialTheme.typography.bodySmall)
                            }
                            if (task.lastError.isNotBlank()) {
                                Text(
                                    task.lastError.take(500),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                "尝试次数：${task.attempts}/${task.maxRetries} · 任务 ID：${task.taskId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (task.status in setOf("RUNNING", "QUEUED", "RETRY_WAIT")) {
                                    OutlinedButton(
                                        enabled = busyTaskId.isBlank(),
                                        onClick = { performTaskAction(task, "pause") }
                                    ) { Text("暂停") }
                                }
                                if (task.status == "PAUSED") {
                                    Button(
                                        enabled = busyTaskId.isBlank(),
                                        onClick = { performTaskAction(task, "resume") }
                                    ) { Text("恢复") }
                                }
                                if (task.status !in setOf("COMPLETED", "CANCELLED", "FAILED")) {
                                    OutlinedButton(
                                        enabled = busyTaskId.isBlank(),
                                        onClick = { performTaskAction(task, "cancel") }
                                    ) { Text("取消") }
                                }
                            }
                            if (expanded) {
                                Text("任务处理器：${task.owner}", style = MaterialTheme.typography.labelSmall)
                                if (task.capabilityId.isNotBlank()) {
                                    Text("能力：${task.capabilityId}", style = MaterialTheme.typography.labelSmall)
                                }
                                Text("执行记录", style = MaterialTheme.typography.titleSmall)
                                if (traces.isEmpty()) {
                                    Text("暂无执行记录", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    traces.forEach { trace ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                "${trace.event} · ${trace.createdAt.toTaskTime()}",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            if (trace.detail.isNotBlank()) {
                                                Text(trace.detail, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text("点击查看执行记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun taskStatusLabel(status: String): String = when (status) {
    "CREATED" -> "已创建"
    "QUEUED" -> "排队中"
    "RUNNING" -> "执行中"
    "RETRY_WAIT" -> "等待重试"
    "WAITING_FOR_USER" -> "等待用户确认"
    "WAITING_FOR_TASK" -> "等待子任务"
    "PAUSED" -> "已暂停"
    "COMPLETED" -> "已完成"
    "FAILED" -> "失败"
    "CANCELLED" -> "已取消"
    else -> status
}

private fun Long.toTaskTime(): String =
    if (this <= 0L) "时间未知" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
