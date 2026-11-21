package app.nestro.media

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun MediaEditorPage(
    context: Context,
    projectId: String,
    onUseChatPrompt: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var assets by remember(projectId) { mutableStateOf(emptyList<MediaAsset>()) }
    var timeline by remember(projectId) { mutableStateOf(MediaTimeline.empty(projectId)) }
    var tasks by remember(projectId) { mutableStateOf(emptyList<MediaRenderTask>()) }
    var status by remember(projectId) { mutableStateOf("导入本地视频、音频或图片，素材只保存在当前项目。") }
    var selectedAssetId by remember(projectId) { mutableStateOf("") }
    var subtitleDraft by remember(projectId) { mutableStateOf("") }
    var subtitleEdits by remember(projectId) { mutableStateOf(emptyMap<String, String>()) }
    var subtitlePage by remember(projectId) { mutableStateOf(0) }
    var topicDraft by remember(projectId) { mutableStateOf("") }
    var audienceDraft by remember(projectId) { mutableStateOf("短视频平台用户") }
    var durationDraft by remember(projectId) { mutableStateOf("60") }
    var exportTaskId by remember(projectId) { mutableStateOf("") }
    var aiAction by remember(projectId) { mutableStateOf("") }
    var showAiConsent by remember(projectId) { mutableStateOf(false) }
    var detectedScenes by remember(projectId) { mutableStateOf(emptyList<MediaScene>()) }
    var imageAnalysis by remember(projectId) { mutableStateOf("") }
    var isSaving by remember(projectId) { mutableStateOf(false) }
    val player = remember(context) { CompositionPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }

    suspend fun reload() {
        val result = withContext(Dispatchers.IO) {
            Triple(
                MediaProjectStore.listAssets(context, projectId),
                MediaProjectStore.readTimeline(context, projectId),
                MediaRenderTaskStore.list(context, projectId)
            )
        }
        assets = result.first
        timeline = result.second
        tasks = result.third
    }

    LaunchedEffect(projectId) {
        runCatching { reload() }.onFailure { status = "读取媒体项目失败：" + (it.message ?: "索引损坏") }
        while (isActive) {
            tasks = withContext(Dispatchers.IO) { MediaRenderTaskStore.list(context, projectId) }
            delay(1_000)
        }
    }

    LaunchedEffect(timeline.revision, assets) {
        val hasVideo = timeline.tracks.any { it.kind == MediaTrackKind.VIDEO && it.clips.isNotEmpty() }
        if (!hasVideo) {
            player.stop()
        } else {
            val composition = withContext(Dispatchers.IO) {
                runCatching { MediaRenderEngine.buildComposition(context, projectId) }
            }
            composition.onSuccess {
                player.setComposition(it)
                player.prepare()
            }.onFailure {
                status = "时间线预览暂不可用：" + (it.message ?: "媒体格式不受支持")
            }
        }
    }

    fun persist(next: MediaTimeline, onSaved: () -> Unit = {}) {
        if (isSaving) {
            status = "上一项时间线修改仍在保存，请稍后再操作。"
            return
        }
        isSaving = true
        val expectedRevision = timeline.revision
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { MediaProjectStore.saveTimeline(context, next, expectedRevision) }
                }
                result.onSuccess {
                    timeline = it
                    onSaved()
                    status = "时间线已保存。"
                }.onFailure {
                    status = "保存失败：" + (it.message ?: "请刷新后重试")
                    runCatching { reload() }
                }
            } finally {
                isSaving = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            status = "正在导入 " + uris.size + " 个素材…"
            val result = withContext(Dispatchers.IO) {
                runCatching { uris.map { MediaProjectStore.importAsset(context, projectId, it) } }
            }
            result.onSuccess {
                reload()
                selectedAssetId = it.firstOrNull()?.id.orEmpty()
                status = "已导入 " + it.size + " 个素材。"
            }.onFailure {
                reload()
                status = "素材导入失败：" + (it.message ?: "文件不受支持")
            }
        }
    }

    val subtitleImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取字幕文件")
                    val bytes = ByteArrayOutputStream()
                    input.use { stream ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 2_000_000) { "字幕文件超过 2 MB" }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    MediaSubtitleCodec.parseSrt(String(bytes.toByteArray(), Charsets.UTF_8))
                }
            }
            result.onSuccess { cues ->
                persist(timeline.copy(subtitles = cues))
                status = "已导入 " + cues.size + " 条字幕。"
            }.onFailure { status = "字幕导入失败：" + (it.message ?: "SRT 格式无效") }
        }
    }

    val subtitleExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = MediaSubtitleCodec.exportSrt(timeline.subtitles)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法写入字幕文件")
                }
            }
            status = if (result.isSuccess) "SRT 字幕已导出。" else "字幕导出失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
        }
    }

    val videoExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? ->
        if (uri != null && exportTaskId.isNotBlank()) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val task = MediaRenderTaskStore.read(context, projectId, exportTaskId) ?: error("导出任务不存在")
                    require(task.status == MediaRenderStatus.COMPLETED) { "任务尚未完成" }
                    val source = MediaProjectStore.exportFile(context, projectId, exportTaskId)
                    require(source.isFile && source.length() > 0) { "导出文件不存在" }
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("无法写入目标文件")
                }
            }
            status = if (result.isSuccess) "视频已保存到所选位置。" else "保存视频失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
        }
    }

    fun addAssetToTimeline(asset: MediaAsset) {
        val kind = if (asset.kind == MediaAssetKind.AUDIO) MediaTrackKind.AUDIO else MediaTrackKind.VIDEO
        var next = timeline
        var track = next.tracks.firstOrNull { it.kind == kind }
        if (track == null) {
            next = MediaTimelinePolicy.addTrack(next, kind)
            track = next.tracks.last()
        }
        runCatching { MediaTimelinePolicy.addClip(next, track.id, asset) }
            .onSuccess(::persist)
            .onFailure { status = "添加剪辑失败：" + (it.message ?: "时间线不允许该操作") }
    }

    fun performAiAction() {
        val provider = MediaIntelligenceRegistry.current()
        val asset = assets.firstOrNull { it.id == selectedAssetId }
        if (provider == null || asset == null) {
            status = "尚未接入媒体 AI Provider，或尚未选择素材。脚本/摘要可先通过聊天模型生成。"
            return
        }
        val requiredCapability = when (aiAction) {
            "transcribe" -> MediaIntelligenceCapability.TRANSCRIPTION
            "scenes" -> MediaIntelligenceCapability.SCENE_DETECTION
            "audio-clean" -> MediaIntelligenceCapability.AUDIO_PROCESSING
            "image" -> MediaIntelligenceCapability.IMAGE_UNDERSTANDING
            else -> null
        }
        if (requiredCapability == null || requiredCapability !in provider.capabilities) {
            status = "当前媒体 AI Provider 不支持此操作，请切换到具备对应能力的服务商。"
            return
        }
        showAiConsent = true
    }

    if (showAiConsent) {
        AlertDialog(
            onDismissRequest = { showAiConsent = false },
            title = { Text("调用媒体 AI 服务") },
            text = {
                val provider = MediaIntelligenceRegistry.current()
                Text(
                    (provider?.displayName ?: "媒体 AI 服务") + " 将处理当前选中的素材。" +
                        if (provider?.requiresNetworkTransfer == true) " 该操作会把素材内容发送到所选服务商，确认前不会上传。" else " 当前 Provider 声明不需要网络传输。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAiConsent = false
                    val provider = MediaIntelligenceRegistry.current() ?: return@TextButton
                    val asset = assets.firstOrNull { it.id == selectedAssetId } ?: return@TextButton
                    scope.launch {
                        status = "媒体 AI 正在分析素材…"
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val file = MediaProjectStore.assetFile(context, asset)
                                when (aiAction) {
                                    "transcribe" -> provider.transcribe(asset, file, "zh-CN")
                                    "scenes" -> provider.detectScenes(asset, file)
                                    "audio-clean" -> {
                                        val processed = provider.processAudio(asset, file, "noise_reduction")
                                        try {
                                            MediaProjectStore.importGeneratedFile(context, projectId, processed, MediaAssetKind.AUDIO)
                                        } finally {
                                            processed.delete()
                                        }
                                    }
                                    "image" -> provider.analyzeImage(asset, file, "请描述图片中的主要内容、可见文字、场景与适合用于视频剪辑的画面要点。")
                                    else -> error("不支持的媒体 AI 操作")
                                }
                            }
                        }
                        result.onSuccess { value ->
                            when (aiAction) {
                                "transcribe" -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val cues = value as List<MediaSubtitleCue>
                                    persist(timeline.copy(subtitles = (timeline.subtitles + cues).sortedBy { it.startMs }))
                                    status = "AI 字幕已生成：" + cues.size + " 条。"
                                }
                                "scenes" -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val scenes = value as List<MediaScene>
                                    detectedScenes = scenes
                                    status = "场景分析完成：" + scenes.size + " 个场景。"
                                }
                                "audio-clean" -> {
                                    reload()
                                    status = "音频处理结果已作为新素材保存，原始音频未修改。"
                                }
                                "image" -> {
                                    imageAnalysis = value as String
                                    status = "图片理解完成。"
                                }
                            }
                        }.onFailure { status = "媒体 AI 处理失败：" + (it.message ?: "未知错误") }
                    }
                }) { Text("确认处理") }
            },
            dismissButton = { TextButton(onClick = { showAiConsent = false }) { Text("取消") } }
        )
    }

    Column(
        Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("智能剪辑", style = MaterialTheme.typography.headlineSmall)
        Text("项目隔离的本地素材库、多轨时间线、非破坏性剪辑、字幕编辑与后台 MP4 导出。", style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodySmall)
        MediaProviderSettingsCard()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importLauncher.launch(arrayOf("video/*", "audio/*", "image/*")) }) { Text("导入素材") }
            OutlinedButton(onClick = { scope.launch { runCatching { reload() }.onFailure { status = it.message ?: "刷新失败" } } }) { Text("刷新") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("预览", style = MaterialTheme.typography.titleMedium)
                val previewPlayer = player
                AndroidView(
                    factory = { viewContext -> PlayerView(viewContext).apply { this.player = previewPlayer; useController = true } },
                    modifier = Modifier.fillMaxWidth().height(190.dp)
                )
                Text("预览当前时间线；剪辑调整后自动刷新，原始素材不会被修改。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("素材库 · " + assets.size, style = MaterialTheme.typography.titleMedium)
        assets.forEach { asset ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(asset.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(asset.kind.name + " · " + formatDuration(asset.durationMs) + " · " + (asset.byteSize / 1024 / 1024) + " MB",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { selectedAssetId = asset.id }) { Text(if (selectedAssetId == asset.id) "预览中" else "预览") }
                    TextButton(onClick = { runCatching { addAssetToTimeline(asset) }.onFailure { status = it.message ?: "添加失败" } }) { Text("加入时间线") }
                    TextButton(onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { MediaProjectStore.deleteAsset(context, projectId, asset.id) }
                            }
                            result.onSuccess {
                                reload()
                                if (selectedAssetId == asset.id) selectedAssetId = ""
                            }.onFailure { status = it.message ?: "素材删除失败" }
                        }
                    }) { Text("删除素材") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                scope.launch { runCatching { timeline = withContext(Dispatchers.IO) { MediaProjectStore.undo(context, projectId) } }
                    .onFailure { status = it.message ?: "撤销失败" } }
            }) { Text("撤销") }
            TextButton(onClick = {
                scope.launch { runCatching { timeline = withContext(Dispatchers.IO) { MediaProjectStore.redo(context, projectId) } }
                    .onFailure { status = it.message ?: "重做失败" } }
            }) { Text("重做") }
            Text("一条主视频轨 + 一条音频混音轨", style = MaterialTheme.typography.bodySmall)
        }
        Text("时间线 · " + formatDuration(timeline.durationMs), style = MaterialTheme.typography.titleMedium)
        timeline.tracks.forEach { track ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(track.name + " · " + track.kind.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (track.kind == MediaTrackKind.AUDIO) {
                            TextButton(onClick = {
                                persist(timeline.copy(tracks = timeline.tracks.map {
                                    if (it.id == track.id) it.copy(muted = !it.muted) else it
                                }))
                            }) { Text(if (track.muted) "取消静音" else "静音") }
                        }
                    }
                    track.clips.sortedBy { it.timelineStartMs }.forEach { clip ->
                        val asset = assets.firstOrNull { it.id == clip.assetId }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(asset?.displayName ?: "缺失素材", style = MaterialTheme.typography.bodySmall)
                                Text(formatDuration(clip.timelineStartMs) + " → " + formatDuration(clip.timelineEndMs),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = {
                                runCatching {
                                    val next = MediaTimelinePolicy.splitClip(timeline, track.id, clip.id, clip.durationMs / 2)
                                    persist(next)
                                }.onFailure { status = it.message ?: "拆分失败" }
                            }) { Text("拆分") }
                            TextButton(onClick = {
                                runCatching {
                                    persist(MediaTimelinePolicy.moveClip(timeline, track.id, clip.id, (clip.timelineStartMs - 1_000).coerceAtLeast(0)))
                                }.onFailure { status = it.message ?: "前移失败" }
                            }) { Text("前移") }
                            TextButton(onClick = {
                                runCatching {
                                    persist(MediaTimelinePolicy.moveClip(timeline, track.id, clip.id, clip.timelineStartMs + 1_000))
                                }.onFailure { status = it.message ?: "后移失败" }
                            }) { Text("后移") }
                            TextButton(onClick = {
                                runCatching {
                                    val start = clip.sourceStartMs + 1_000
                                    require(start < clip.sourceEndMs) { "片段不足 1 秒，无法裁剪头部" }
                                    persist(MediaTimelinePolicy.trimClip(timeline, track.id, clip.id, start, clip.sourceEndMs))
                                }.onFailure { status = it.message ?: "头部裁剪失败" }
                            }) { Text("裁头") }
                            TextButton(onClick = {
                                runCatching {
                                    val end = clip.sourceEndMs - 1_000
                                    require(end > clip.sourceStartMs) { "片段不足 1 秒，无法裁剪尾部" }
                                    persist(MediaTimelinePolicy.trimClip(timeline, track.id, clip.id, clip.sourceStartMs, end))
                                }.onFailure { status = it.message ?: "尾部裁剪失败" }
                            }) { Text("裁尾") }
                            TextButton(onClick = {
                                persist(timeline.copy(tracks = timeline.tracks.map { current ->
                                    if (current.id == track.id) current.copy(clips = current.clips.map {
                                        if (it.id == clip.id) it.copy(muted = !it.muted) else it
                                    }) else current
                                }))
                            }) { Text(if (clip.muted) "取消静音" else "静音") }
                            TextButton(onClick = { persist(MediaTimelinePolicy.removeClip(timeline, track.id, clip.id)) }) { Text("删除") }
                        }
                    }
                }
            }
        }
        Text("字幕 · " + timeline.subtitles.size, style = MaterialTheme.typography.titleMedium)
        TextField(
            value = subtitleDraft, onValueChange = { subtitleDraft = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("新增字幕内容") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val start = timeline.durationMs
                if (subtitleDraft.isNotBlank()) {
                    val cue = MediaSubtitleCue(startMs = start, endMs = start + 3_000, text = subtitleDraft.trim())
                    persist(timeline.copy(subtitles = timeline.subtitles + cue))
                    subtitleDraft = ""
                }
            }) { Text("添加字幕") }
            OutlinedButton(onClick = { subtitleImportLauncher.launch(arrayOf("application/x-subrip", "text/plain")) }) { Text("导入 SRT") }
            OutlinedButton(enabled = timeline.subtitles.isNotEmpty(), onClick = { subtitleExportLauncher.launch("nestro-subtitles.srt") }) { Text("导出 SRT") }
        }
        val subtitlePageCount = (timeline.subtitles.size + 99) / 100
        val safeSubtitlePage = subtitlePage.coerceIn(0, (subtitlePageCount - 1).coerceAtLeast(0))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (timeline.subtitles.isEmpty()) "暂无字幕" else "显示第 " + (safeSubtitlePage * 100 + 1) + "-" + minOf((safeSubtitlePage + 1) * 100, timeline.subtitles.size) + " 条 / 共 " + timeline.subtitles.size + " 条",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall
            )
            TextButton(enabled = safeSubtitlePage > 0, onClick = { subtitlePage = safeSubtitlePage - 1 }) { Text("上一页") }
            TextButton(enabled = safeSubtitlePage + 1 < subtitlePageCount, onClick = { subtitlePage = safeSubtitlePage + 1 }) { Text("下一页") }
        }
        timeline.subtitles.drop(safeSubtitlePage * 100).take(100).forEach { cue ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatDuration(cue.startMs) + " - " + formatDuration(cue.endMs), style = MaterialTheme.typography.labelSmall)
                    TextField(
                        value = subtitleEdits[cue.id] ?: cue.text,
                        onValueChange = { value -> subtitleEdits = subtitleEdits + (cue.id to value.take(5_000)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("字幕内容") }
                    )
                }
                TextButton(onClick = {
                    val shift = (cue.startMs - 1_000).coerceAtLeast(0)
                    persist(timeline.copy(subtitles = timeline.subtitles.map {
                        if (it.id == cue.id) it.copy(startMs = shift, endMs = shift + (cue.endMs - cue.startMs)) else it
                    }))
                }) { Text("-1秒") }
                TextButton(onClick = {
                    val shift = cue.startMs + 1_000
                    persist(timeline.copy(subtitles = timeline.subtitles.map {
                        if (it.id == cue.id) it.copy(startMs = shift, endMs = shift + (cue.endMs - cue.startMs)) else it
                    }))
                }) { Text("+1秒") }
                TextButton(onClick = {
                    val editedText = subtitleEdits[cue.id]
                    if (editedText != null) {
                        persist(timeline.copy(subtitles = timeline.subtitles.map { if (it.id == cue.id) it.copy(text = editedText) else it })) {
                            subtitleEdits = subtitleEdits - cue.id
                        }
                    }
                }, enabled = subtitleEdits.containsKey(cue.id)) { Text("保存") }
                TextButton(onClick = {
                    subtitleEdits = subtitleEdits - cue.id
                    persist(timeline.copy(subtitles = timeline.subtitles.filterNot { it.id == cue.id }))
                }) { Text("删除") }
            }
        }
        Divider()
        Text("AI 协作", style = MaterialTheme.typography.titleMedium)
        TextField(value = topicDraft, onValueChange = { topicDraft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("视频主题") })
        TextField(value = audienceDraft, onValueChange = { audienceDraft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("目标受众") })
        TextField(value = durationDraft, onValueChange = { durationDraft = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(), label = { Text("目标时长（秒）") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                onUseChatPrompt(MediaPromptFactory.suggestScript(topicDraft, durationDraft.toIntOrNull() ?: 60, audienceDraft))
            }) { Text("生成脚本") }
            OutlinedButton(onClick = {
                onUseChatPrompt(MediaPromptFactory.suggestShotList(topicDraft, timeline.subtitles.joinToString("\n") { it.text }))
            }) { Text("生成分镜") }
            OutlinedButton(onClick = {
                onUseChatPrompt(MediaPromptFactory.summarize(timeline.subtitles.joinToString("\n") { it.text }))
            }) { Text("摘要文案") }
        }
        val selected = assets.firstOrNull { it.id == selectedAssetId }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = selected != null && selected.kind != MediaAssetKind.IMAGE,
                onClick = { aiAction = "transcribe"; performAiAction() }) { Text("AI 转字幕") }
            OutlinedButton(enabled = selected?.kind == MediaAssetKind.VIDEO,
                onClick = { aiAction = "scenes"; performAiAction() }) { Text("识别场景") }
            OutlinedButton(enabled = selected?.kind == MediaAssetKind.AUDIO,
                onClick = { aiAction = "audio-clean"; performAiAction() }) { Text("音频处理") }
            OutlinedButton(enabled = selected?.kind == MediaAssetKind.IMAGE,
                onClick = { aiAction = "image"; performAiAction() }) { Text("理解图片") }
        }
        if (imageAnalysis.isNotBlank()) {
            Text("图片理解结果", style = MaterialTheme.typography.titleSmall)
            Text(imageAnalysis, style = MaterialTheme.typography.bodySmall)
        }
        detectedScenes.forEach { scene ->
            Text(formatDuration(scene.startMs) + " - " + formatDuration(scene.endMs) + " · " + scene.title,
                style = MaterialTheme.typography.bodySmall)
        }
        Divider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = timeline.tracks.any { it.kind == MediaTrackKind.VIDEO && it.clips.isNotEmpty() },
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { MediaRenderTaskStore.create(context, projectId) }
                        }
                        result.onSuccess { task ->
                            tasks = listOf(task) + tasks
                            status = "导出任务已排队；暂停后恢复会从头重新渲染。"
                            MediaRenderScheduler.enqueue(context, projectId, task.id)
                        }.onFailure { status = "无法创建导出任务：" + (it.message ?: "未知错误") }
                    }
                }) { Text("导出 MP4") }
        }
        tasks.forEach { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.status.name + " · " + task.progress + "%", style = MaterialTheme.typography.titleSmall)
                    if (task.error.isNotBlank()) Text(task.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (task.status == MediaRenderStatus.RUNNING || task.status == MediaRenderStatus.QUEUED) {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { MediaRenderScheduler.pause(context, projectId, task.id) } }
                                        .onFailure { status = it.message ?: "暂停失败" }
                                }
                            }) { Text("暂停") }
                        }
                        if (task.status == MediaRenderStatus.PAUSED) {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { MediaRenderScheduler.resume(context, projectId, task.id) } }
                                        .onFailure { status = it.message ?: "恢复失败" }
                                }
                            }) { Text("从头恢复") }
                        }
                        if (task.status == MediaRenderStatus.RUNNING || task.status == MediaRenderStatus.QUEUED || task.status == MediaRenderStatus.PAUSED) {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { MediaRenderScheduler.cancel(context, projectId, task.id) } }
                                        .onFailure { status = it.message ?: "取消失败" }
                                }
                            }) { Text("取消") }
                        }
                        if (task.status == MediaRenderStatus.COMPLETED) {
                            TextButton(onClick = { exportTaskId = task.id; videoExportLauncher.launch("nestro-export.mp4") }) { Text("保存到手机") }
                        }
                        if (task.status in setOf(MediaRenderStatus.COMPLETED, MediaRenderStatus.FAILED, MediaRenderStatus.CANCELLED)) {
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { MediaRenderTaskStore.delete(context, projectId, task.id) }
                                    tasks = MediaRenderTaskStore.list(context, projectId)
                                }
                            }) { Text("清理任务") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
