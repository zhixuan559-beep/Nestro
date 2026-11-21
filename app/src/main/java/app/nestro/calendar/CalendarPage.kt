package app.nestro.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val calendarDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
private val calendarTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

@Composable
fun CalendarPage(repository: CalendarRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var visibleMonth by remember { mutableStateOf(YearMonth.now()) }
    var showEditor by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status = if (granted) "日程通知已授权" else "通知权限未开启，日程仍会保存，但不会弹出通知"
    }

    fun refresh() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching { repository.allEvents() } }
            events = result.getOrDefault(emptyList())
            status = result.exceptionOrNull()?.let { "读取日历失败：${it.message ?: "未知错误"}" } ?: status
            loading = false
        }
    }

    LaunchedEffect(repository) {
        withContext(Dispatchers.IO) { repository.reconcileSchedules() }
        refresh()
    }

    val zone = remember { ZoneId.systemDefault() }
    val from = remember(visibleMonth) {
        visibleMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val until = remember(visibleMonth) {
        visibleMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val monthOccurrences = remember(events, from, until) {
        CalendarRules.occurrences(events, from, until)
    }
    val dayOccurrences = remember(events, selectedDate) {
        val dayStart = selectedDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        CalendarRules.occurrences(events, dayStart, dayEnd)
    }
    val firstWeekdayOffset = visibleMonth.atDay(1).dayOfWeek.value - 1
    val dayCount = visibleMonth.lengthOfMonth()
    val cells = (0 until 42).map { index ->
        val day = index - firstWeekdayOffset + 1
        if (day in 1..dayCount) visibleMonth.atDay(day) else null
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("日历", style = MaterialTheme.typography.headlineSmall)
                Text("本机保存 · 支持重复日程与后台提醒", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                selectedDate = LocalDate.now()
                visibleMonth = YearMonth.now()
            }) { Text("今天") }
            Button(onClick = { editing = null; showEditor = true }) { Text("新建") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { visibleMonth = visibleMonth.minusMonths(1) }) { Text("‹") }
            Text(visibleMonth.format(DateTimeFormatter.ofPattern("yyyy年M月")), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { visibleMonth = visibleMonth.plusMonths(1) }) { Text("›") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().height(252.dp),
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(cells.size) { index ->
                val date = cells[index]
                val count = if (date == null) 0 else monthOccurrences.count {
                    Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == date
                }
                Surface(
                    onClick = { if (date != null) selectedDate = date },
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        date == null -> MaterialTheme.colorScheme.surface
                        date == selectedDate -> MaterialTheme.colorScheme.primaryContainer
                        date == LocalDate.now() -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(date?.dayOfMonth?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        if (count > 0) Text("•".repeat(count.coerceAtMost(3)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selectedDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showCompleted = !showCompleted }) { Text(if (showCompleted) "查看未完成" else "已完成") }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("开启提醒") }
            }
        }
        val completedForDay = events.filter { event ->
            event.completed && Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDate() == selectedDate
        }
        val visibleOccurrences = if (showCompleted) completedForDay.map {
            CalendarRules.Occurrence(it, it.startAt, it.endAt)
        } else dayOccurrences
        if (loading) Text("正在读取日程…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (visibleOccurrences.isEmpty()) Text(if (showCompleted) "这一天没有已完成日程" else "这一天没有安排", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleOccurrences, key = { "${it.event.id}-${it.startAt}" }) { occurrence ->
                val event = occurrence.event
                Card(Modifier.fillMaxWidth().clickable { editing = event; showEditor = true }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(event.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { repository.setCompleted(event.id, !event.completed) }
                                    refresh()
                                }
                            }) { Text(if (event.completed) "恢复" else "完成") }
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { repository.delete(event.id) }
                                    refresh()
                                }
                            }) { Text("删除") }
                        }
                        val start = Instant.ofEpochMilli(occurrence.startAt).atZone(zone)
                        val end = Instant.ofEpochMilli(occurrence.endAt).atZone(zone)
                        Text(
                            if (event.allDay) "全天" else "${start.format(calendarTimeFormat)} – ${end.format(calendarTimeFormat)}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (event.location.isNotBlank()) Text("地点：${event.location}", style = MaterialTheme.typography.bodySmall)
                        if (event.description.isNotBlank()) Text(event.description, style = MaterialTheme.typography.bodySmall)
                        if (event.recurrence != CalendarRules.NONE) Text("重复：${event.recurrence}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    if (showEditor) {
        CalendarEventEditor(
            initial = editing,
            defaultDate = selectedDate,
            onDismiss = { showEditor = false },
            onSave = { title, description, location, date, startTime, endTime, allDay, recurrence, reminder ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val start = if (allDay) CalendarRules.dateTime(date, 0, 0) else {
                                val t = java.time.LocalTime.parse(startTime, calendarTimeFormat)
                                CalendarRules.dateTime(date, t.hour, t.minute)
                            }
                            val end = if (allDay) CalendarRules.dateTime(date.plusDays(1), 0, 0) else {
                                val t = java.time.LocalTime.parse(endTime, calendarTimeFormat)
                                CalendarRules.dateTime(date, t.hour, t.minute)
                            }
                            require(end > start) { "结束时间必须晚于开始时间" }
                            val old = editing
                            repository.save(
                                CalendarEvent(
                                    id = old?.id ?: java.util.UUID.randomUUID().toString(),
                                    title = title, description = description, location = location,
                                    startAt = start, endAt = end, allDay = allDay,
                                    recurrence = recurrence, reminderMinutes = reminder,
                                    completed = false, createdAt = old?.createdAt ?: System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    status = result.fold({ "日程已保存，提醒已安排" }, { it.message ?: "保存失败" })
                    if (result.isSuccess) {
                        selectedDate = date
                        visibleMonth = YearMonth.from(date)
                        showEditor = false
                        refresh()
                    }
                }
            }
        )
    }
}

@Composable
private fun CalendarEventEditor(
    initial: CalendarEvent?,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, String, String, LocalDate, String, String, Boolean, String, Int) -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var location by remember(initial) { mutableStateOf(initial?.location.orEmpty()) }
    var date by remember(initial) {
        mutableStateOf(
            initial?.let { Instant.ofEpochMilli(it.startAt).atZone(ZoneId.systemDefault()).toLocalDate() } ?: defaultDate
        )
    }
    var startTime by remember(initial) {
        mutableStateOf(initial?.let { Instant.ofEpochMilli(it.startAt).atZone(ZoneId.systemDefault()).format(calendarTimeFormat) } ?: "09:00")
    }
    var endTime by remember(initial) {
        mutableStateOf(initial?.let { Instant.ofEpochMilli(it.endAt).atZone(ZoneId.systemDefault()).format(calendarTimeFormat) } ?: "10:00")
    }
    var allDay by remember(initial) { mutableStateOf(initial?.allDay ?: false) }
    var recurrence by remember(initial) { mutableStateOf(initial?.recurrence ?: CalendarRules.NONE) }
    var reminder by remember(initial) { mutableStateOf((initial?.reminderMinutes ?: 10).toString()) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建日程" else "编辑日程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(140) }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(date.format(calendarDateFormat), { value ->
                    runCatching { date = LocalDate.parse(value, calendarDateFormat); error = "" }
                        .onFailure { error = "日期格式请使用 yyyy-MM-dd" }
                }, label = { Text("日期（yyyy-MM-dd）") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startTime, { startTime = it.take(5) }, modifier = Modifier.weight(1f), label = { Text("开始 HH:mm") }, singleLine = true, enabled = !allDay)
                    OutlinedTextField(endTime, { endTime = it.take(5) }, modifier = Modifier.weight(1f), label = { Text("结束 HH:mm") }, singleLine = true, enabled = !allDay)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allDay, { allDay = it })
                    Text("全天")
                }
                OutlinedTextField(location, { location = it.take(300) }, label = { Text("地点（可选）" ) }, singleLine = true)
                OutlinedTextField(description, { description = it.take(4000) }, label = { Text("备注（可选）") }, minLines = 2)
                OutlinedTextField(
                    recurrence, { value ->
                        recurrence = value.uppercase(Locale.ROOT).take(8)
                    }, label = { Text("重复规则：NONE / DAILY / WEEKLY / MONTHLY / YEARLY") }, singleLine = true
                )
                OutlinedTextField(
                    reminder, { reminder = it.filter { ch -> ch.isDigit() || ch == '-' }.take(5) },
                    label = { Text("提前提醒分钟（-1 表示关闭；0/5/10/15/30/60/1440）") }, singleLine = true
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val reminderValue = reminder.toIntOrNull()
                val validReminder = reminderValue != null && reminderValue in setOf(-1, 0, 5, 10, 15, 30, 60, 1440)
                val validRecurrence = recurrence in CalendarRules.recurrenceValues
                val validTimes = allDay || runCatching {
                    java.time.LocalTime.parse(startTime, calendarTimeFormat)
                    java.time.LocalTime.parse(endTime, calendarTimeFormat)
                }.isSuccess
                if (title.isBlank()) error = "请填写日程标题"
                else if (!validTimes) error = "时间格式请使用 HH:mm"
                else if (!validReminder) error = "提醒分钟数无效"
                else if (!validRecurrence) error = "重复规则无效"
                else {
                    error = ""
                    onSave(title, description, location, date, startTime, endTime, allDay, recurrence, reminderValue!!)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
