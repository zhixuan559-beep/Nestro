package app.nestro.ui

import app.nestro.NestroApplication
import app.nestro.data.PhoneAgentTaskStore
import app.nestro.phone.PhoneAgentPlanner
import app.nestro.phone.PhoneAgentService
import app.nestro.phone.PhoneAgentExecutionEnvironmentProvider
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun PhoneAgentPage(context: Context, projectId: String) {
    val prefs = remember { context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val app = remember { context.applicationContext as NestroApplication }
    val taskService = remember { app.phoneAgentTaskService }
    val displayController = remember { app.mikaVirtualDisplayController }
    val helperBinder by displayController.helperState().collectAsState()
    var pairingPorts by remember { mutableStateOf(emptyList<Int>()) }
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var virtualSession by remember { mutableStateOf(displayController.currentSession()) }
    val executionAvailable = PhoneAgentExecutionEnvironmentProvider.active?.hasValidGrant() == true
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf("尚未读取手机界面") }
    var tasks by remember { mutableStateOf(emptyList<app.nestro.data.PhoneAgentTaskEntity>()) }
    var taskLogs by remember { mutableStateOf(emptyMap<String, List<app.nestro.data.PhoneAgentTaskLogEntity>>()) }
    var taskClarifications by remember { mutableStateOf(emptyMap<String, String>()) }
    var taskMessage by remember { mutableStateOf("") }
    val enabledServices = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    val serviceEnabled = enabledServices.contains(
        context.packageName + "/app.nestro.phone.PhoneAgentService",
        ignoreCase = true
    )
    val grantUntil = prefs.getLong("phone_task_grant_until", 0L)
    val hasTaskGrant = grantUntil > System.currentTimeMillis()
    val hasSingleGrant = prefs.getBoolean("phone_single_grant", false)

    LaunchedEffect(refresh, projectId) {
        tasks = withContext(Dispatchers.IO) {
            runCatching { taskService.loadTasks(projectId) }.getOrDefault(emptyList())
        }
        taskLogs = withContext(Dispatchers.IO) {
            taskService.loadLogs(tasks)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("手机自动化", color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text("Phone Agent 会先建立任务计划，再在授权范围内执行可验证的低风险动作。支付、验证码和最终提交始终由你接手。", color = Muted)
        val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted -> taskMessage = if (granted) "任务通知已开启" else "未授予通知权限，任务仍可运行但不会显示系统通知" }
        val notificationsAllowed = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!notificationsAllowed) {
            OutlinedButton(onClick = { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                Text("开启后台任务通知")
            }
        }
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (serviceEnabled) "虚拟屏界面识别桥接已开启" else "虚拟屏界面识别桥接尚未开启", color = if (serviceEnabled) Mint else Ink)
                Text("该桥接仅用于识别隔离虚拟屏内的界面节点；应用启动、坐标触控与返回由虚拟屏助手执行，不会接管真实主屏。需要你在系统设置中手动开启。", color = Muted, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                ) { Text("前往系统无障碍设置") }
            }
        }
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("隔离虚拟屏", color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        virtualSession != null && displayController.virtualExecutionPreferred -> "已连接隔离虚拟屏，Phone Agent 仅在独立显示中执行"
                        displayController.virtualExecutionPreferred -> "已选择隔离执行，但当前会话未恢复；任务会暂停，不会自动切回主屏"
                        helperBinder != null -> "无线调试助手已连接，可以创建隔离屏"
                        else -> "尚未连接无线调试助手"
                    },
                    color = if (helperBinder != null && virtualSession != null) Mint else Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                if (helperBinder == null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = runCatching { displayController.connectHelper() }
                                taskMessage = result.fold({ if (it) "已重新连接配对助手" else "助手尚未连接" }, { "重连失败：${it.message ?: "请确认无线调试仍开启"}" })
                                refresh++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重连已配对助手") }
                    Text("首次连接时，在 Android 开发者选项中开启「无线调试」，进入「使用配对码配对设备」，再填写系统显示的配对端口和六位配对码。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val found = runCatching { displayController.findPairingPorts() }.getOrDefault(emptyList())
                                pairingPorts = found
                                if (pairingPort.isBlank()) pairingPort = found.firstOrNull()?.toString().orEmpty()
                                taskMessage = if (found.isEmpty()) "未发现配对端口，请确认无线调试配对页面仍处于打开状态。" else "发现配对端口：${found.joinToString("、")}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("查找无线调试配对端口") }
                    if (pairingPorts.isNotEmpty()) {
                        Text("发现端口：${pairingPorts.joinToString("、")}", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedTextField(
                        value = pairingPort,
                        onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("配对端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("六位配对码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                taskMessage = "正在配对并启动隔离助手…"
                                val result = runCatching {
                                    displayController.pair(pairingPort.toInt(), pairingCode)
                                    displayController.connectHelper()
                                }
                                taskMessage = result.fold({ if (it) "无线调试助手已连接" else "助手尚未完成连接" }, { "连接失败：${it.message ?: "请检查配对端口和配对码"}" })
                                pairingCode = ""
                                refresh++
                            }
                        },
                        enabled = pairingPort.toIntOrNull()?.let { it in 1..65535 } == true && pairingCode.length == 6,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                    ) { Text("配对并连接助手") }
                } else {
                    if (virtualSession == null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    taskMessage = "正在创建独立虚拟屏…"
                                    val result = runCatching {
                                        displayController.createSession(width = 540, height = 960, densityDpi = 420)
                                    }
                                    result.onSuccess { session ->
                                        virtualSession = session
                                        PhoneAgentService.activeService?.let(displayController::activatePhoneAgent)
                                        taskMessage = "隔离虚拟屏已创建"
                                    }.onFailure { taskMessage = "创建失败：${it.message ?: "虚拟屏不可用"}" }
                                    refresh++
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                        ) { Text("创建隔离虚拟屏") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("实时执行画面", color = Ink, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                val service = PhoneAgentService.activeService
                                if (service != null && displayController.activatePhoneAgent(service)) taskMessage = "Phone Agent 已切换到隔离虚拟屏"
                                else taskMessage = "虚拟屏界面识别桥接尚未连接，无法启用执行环境"
                            }) { Text("使用隔离屏", color = Mint) }
                        }
                        Text("实时画面无标题栏悬浮；单击切换大小，双击或长按屏幕触控，拖动屏幕滑动，拖动任一角调整大小。", color = Muted, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {

                            TextButton(
                                onClick = {
                                    virtualSession?.close()
                                    virtualSession = null
                                    taskMessage = "隔离虚拟屏已关闭；Phone Agent 任务将暂停，不会回退到真实主屏"
                                    refresh++
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("关闭隔离屏", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }

            }
        }
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次授权", color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        hasTaskGrant -> "任务持续授权有效，剩余约 ${((grantUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(1)} 分钟"
                        hasSingleGrant -> "单次操作授权有效"
                        else -> "当前没有操作授权"
                    },
                    color = if (hasTaskGrant || hasSingleGrant) Mint else Muted
                )
                Button(
                    onClick = { prefs.edit().putBoolean("phone_single_grant", true).apply(); refresh++ },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("授予单次操作") }
                OutlinedButton(
                    onClick = { prefs.edit().putLong("phone_task_grant_until", System.currentTimeMillis() + 2 * 60 * 60 * 1000L).apply(); refresh++ },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("授予本次任务持续授权（最长 2 小时）") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            tasks.firstOrNull { it.status == PhoneAgentTaskStore.STATUS_RUNNING }?.id?.let { taskId ->
                                PhoneAgentService.activeService?.requestTaskStop(taskId)
                                taskMessage = "已请求停止任务：${taskId.take(8)}"
                            } ?: run { taskMessage = "当前没有正在运行的任务" }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("停止当前任务") }
                    TextButton(
                        onClick = {
                            prefs.edit().remove("phone_single_grant").remove("phone_task_grant_until").apply()
                            tasks.firstOrNull { it.status == PhoneAgentTaskStore.STATUS_RUNNING }?.id?.let { taskId ->
                                PhoneAgentService.activeService?.requestTaskStop(taskId)
                                taskMessage = "授权已撤销，正在停止当前任务"
                            } ?: run { taskMessage = "授权已撤销" }
                            refresh++
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("撤销全部授权", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        OutlinedButton(
            onClick = { snapshot = PhoneAgentExecutionEnvironmentProvider.active?.captureVisibleText() ?: "执行环境尚未连接，请先连接并启用隔离虚拟屏执行环境。" },
            enabled = serviceEnabled,
            modifier = Modifier.fillMaxWidth()
        ) { Text("读取当前界面（仅本次主动触发）") }
        Text(snapshot, color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                val undone = virtualSession?.let { session -> PhoneAgentService.activeService?.undoLastTextEdit(session.displayId) } == true
                taskMessage = if (undone) "已撤销上一次安全文本填写" else "当前没有可撤销的安全文本填写，或页面已发生变化"
                refresh++
            },
            enabled = serviceEnabled && virtualSession != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("撤销上次安全文本填写") }
        Text("仅支持页面未变化时撤销最后一次非敏感文本填写；需要有效操作授权。", color = Muted, style = MaterialTheme.typography.labelSmall)
        if (taskMessage.isNotBlank()) Text(taskMessage, color = Mint, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("任务记录", color = Ink, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text("刷新", color = Mint) }
        }
        if (tasks.isEmpty()) {
            Text("当前项目还没有 Phone Agent 任务。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            tasks.forEach { task ->
                val canResume = runCatching { PhoneAgentTaskStore.decodeActions(task.planJson) }.isSuccess
                val canStartAgent = !canResume && task.status in setOf(PhoneAgentTaskStore.STATUS_PLANNED, PhoneAgentTaskStore.STATUS_PAUSED, PhoneAgentTaskStore.STATUS_RUNNING, PhoneAgentTaskStore.STATUS_WAITING_USER, PhoneAgentTaskStore.STATUS_FAILED)
                Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(task.goal, color = Ink, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "状态：${task.status} · 进度：${task.currentStep} · ${task.category}",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (task.status in setOf(PhoneAgentTaskStore.STATUS_WAITING_USER, PhoneAgentTaskStore.STATUS_PAUSED)) {
                            OutlinedTextField(
                                value = taskClarifications[task.id] ?: "",
                                onValueChange = { taskClarifications = taskClarifications + (task.id to it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("补充预算、规格或其他非敏感要求…") },
                                minLines = 2
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    val clarification = taskClarifications[task.id].orEmpty()
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { taskService.appendClarification(task.id, clarification) }
                                    }
                                    taskMessage = result.fold({ "补充信息已保存，任务已重新规划" }, { "补充失败：${it.message ?: "内容无效"}" })
                                    if (result.isSuccess && executionAvailable) {
                                        withContext(Dispatchers.IO) { taskService.enqueue(task.id) }
                                    }
                                    if (result.isSuccess) taskClarifications = taskClarifications - task.id
                                    refresh++
                                }
                            }) { Text("补充信息并重新规划", color = Mint) }
                        }
                        taskLogs[task.id].orEmpty().takeLast(12).forEach { log ->
                            Text(
                                "${if (log.success) "✓" else "!"} ${log.action} · ${log.detail}",
                                color = if (log.success) Muted else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if ((canResume && task.status in setOf(
                                    PhoneAgentTaskStore.STATUS_PLANNED,
                                    PhoneAgentTaskStore.STATUS_PAUSED,
                                    PhoneAgentTaskStore.STATUS_RUNNING,
                                    PhoneAgentTaskStore.STATUS_WAITING_USER,
                                    PhoneAgentTaskStore.STATUS_FAILED
                                )) || canStartAgent
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    taskService.enqueue(
                                                        task.id,
                                                        afterUserHandoff = task.status == PhoneAgentTaskStore.STATUS_WAITING_USER,
                                                        allowRetryCurrentAction = task.status in setOf(PhoneAgentTaskStore.STATUS_RUNNING, PhoneAgentTaskStore.STATUS_FAILED)
                                                    )
                                                }
                                            }.onSuccess {
                                                taskMessage = "已提交统一任务队列，切换会话不会中断任务记录。"
                                            }.onFailure {
                                                taskMessage = "任务提交失败：${it.message ?: "未知错误"}"
                                            }
                                            refresh++
                                        }
                                    },
                                    enabled = serviceEnabled && executionAvailable && (hasTaskGrant || hasSingleGrant),
                                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)
                                ) { Text(when { task.status == PhoneAgentTaskStore.STATUS_WAITING_USER -> "我已处理，继续"; canStartAgent -> "启动 Phone Agent"; task.status == PhoneAgentTaskStore.STATUS_RUNNING -> "核对页面后继续"; task.status == PhoneAgentTaskStore.STATUS_FAILED -> "核对页面后重试"; else -> "继续任务" }) }
                            }
                            if (task.status !in setOf(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.STATUS_CANCELLED)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            taskService.cancel(
                                                task.id,
                                                stopCurrent = task.status == PhoneAgentTaskStore.STATUS_RUNNING
                                            )
                                        }
                                        refresh++
                                    }
                                }) { Text("取消任务", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
        Text("安全边界：不自动填写密码或验证码，不绕过登录/风控，不替你完成付款或不可逆提交。网页检索会向搜索服务发送检索词，含手机号、邮箱、卡号等敏感信息会被拦截。操作记录保存在本机。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    virtualSession?.let { PhoneAgentFloatingPreview(it) }
}


@Composable
private fun PhoneAgentFloatingPreview(session: app.nestro.phone.virtualdisplay.mika.MikaVirtualDisplayController.Session) {
    val scope = rememberCoroutineScope()
    val virtualFrame by session.frames.collectAsState()
    var previewScale by remember(session) { mutableFloatStateOf(0.68f) }
    val previewWidthDp = 180.dp * previewScale
    val frameAspect = virtualFrame?.let { it.height.toFloat() / it.width.toFloat() } ?: (320f / 180f)
    val previewHeightDp = previewWidthDp * frameAspect

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = Card,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 8.dp,
            modifier = Modifier
                .padding(end = 12.dp, bottom = 12.dp)
                .width(previewWidthDp)
                .height(previewHeightDp)
        ) {
            val bitmap = virtualFrame
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "隔离虚拟屏实时画面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(session, previewScale) {
                            detectTapGestures(
                                onTap = {
                                    previewScale = if (previewScale < 1f) 1.4f else 0.68f
                                },
                                onDoubleTap = { offset ->
                                    scope.launch {
                                        session.pipInput.tap(
                                            (offset.x / size.width).coerceIn(0f, 1f),
                                            (offset.y / size.height).coerceIn(0f, 1f)
                                        )
                                    }
                                },
                                onLongPress = { offset ->
                                    scope.launch {
                                        session.pipInput.tap(
                                            (offset.x / size.width).coerceIn(0f, 1f),
                                            (offset.y / size.height).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                            )
                        }
                        .pointerInput(session, previewScale) {
                            var resizing = false
                            val points = mutableListOf<Pair<Float, Float>>()
                            val corner = 26.dp.toPx()
                            detectDragGestures(
                                onDragStart = { start ->
                                    resizing =
                                        (start.x <= corner || start.x >= size.width - corner) &&
                                        (start.y <= corner || start.y >= size.height - corner)
                                    points.clear()
                                    if (!resizing) {
                                        points += (start.x / size.width).coerceIn(0f, 1f) to
                                            (start.y / size.height).coerceIn(0f, 1f)
                                    }
                                },
                                onDragEnd = {
                                    if (!resizing && points.size >= 2) {
                                        val path = points.toList()
                                        scope.launch { session.pipInput.swipe(path, 500L) }
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    if (resizing) {
                                        previewScale = (previewScale + dragAmount.y / 420f).coerceIn(0.45f, 1.4f)
                                    } else {
                                        points += (change.position.x / size.width).coerceIn(0f, 1f) to
                                            (change.position.y / size.height).coerceIn(0f, 1f)
                                    }
                                    change.consume()
                                }
                            )
                        }
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("等待虚拟屏画面…", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
