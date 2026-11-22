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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun MediaEditorPage(
    context: Context,
    projectId: String,
    initialAssetId: String = "",
    onUseChatPrompt: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var assets by remember(projectId) { mutableStateOf(emptyList<MediaAsset>()) }
    var timeline by remember(projectId) { mutableStateOf(MediaTimeline.empty(projectId)) }
    val latestTimeline by rememberUpdatedState(timeline)
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
    var videoPromptDraft by remember(projectId) { mutableStateOf("") }
    var activeMediaAiTaskId by remember(projectId) { mutableStateOf("") }
    var mediaAiTasks by remember(projectId) { mutableStateOf(emptyList<MediaAiTask>()) }
    var mediaAiProgress by remember(projectId) { mutableStateOf(0f) }
    var mediaAiRunning by remember(projectId) { mutableStateOf(false) }
    var isSaving by remember(projectId) { mutableStateOf(false) }
    var pendingMediaArchiveUri by remember(projectId) { mutableStateOf<Uri?>(null) }
    var showMediaArchiveImportConfirm by remember(projectId) { mutableStateOf(false) }
    val player = remember(context) { CompositionPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    val assetPreviewPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    DisposableEffect(assetPreviewPlayer) { onDispose { assetPreviewPlayer.release() } }

    suspend fun reload() {
        val result = withContext(Dispatchers.IO) {
            Triple(
                MediaProjectStore.listAssets(context, projectId),
                MediaProjectStore.readTimeline(context, projectId),
                MediaRenderScheduler.list(context, projectId)
            )
        }
        assets = result.first
        timeline = result.second
        tasks = result.third
    }

    LaunchedEffect(projectId) {
        runCatching { reload() }.onFailure { status = "读取媒体项目失败：" + (it.message ?: "索引损坏") }
        withContext(Dispatchers.IO) { MediaAiTaskScheduler.recover(context, projectId) }
        val pendingResults = withContext(Dispatchers.IO) { MediaAiTaskStore.pendingResults(context, projectId) }
        val interrupted = withContext(Dispatchers.IO) { MediaAiTaskStore.recoverable(context, projectId) }
        if (activeMediaAiTaskId.isBlank()) activeMediaAiTaskId = pendingResults.firstOrNull()?.id ?: interrupted.firstOrNull()?.id.orEmpty()
        while (isActive) {
            tasks = withContext(Dispatchers.IO) { MediaRenderScheduler.list(context, projectId) }
            mediaAiTasks = withContext(Dispatchers.IO) { MediaAiTaskScheduler.list(context, projectId) }
            if (imageAnalysis.isBlank()) {
                val savedImage = mediaAiTasks.firstOrNull { it.action == "image" && it.status == MediaAiTaskStatus.COMPLETED && it.resultJson.isNotBlank() }
                if (savedImage != null) imageAnalysis = runCatching { JSONObject(savedImage.resultJson).optString("text") }.getOrDefault("")
            }
            if (detectedScenes.isEmpty()) {
                val savedScenes = mediaAiTasks.firstOrNull { it.action == "scenes" && it.status == MediaAiTaskStatus.COMPLETED && it.resultJson.isNotBlank() }
                if (savedScenes != null) detectedScenes = runCatching {
                    val rows = JSONObject(savedScenes.resultJson).optJSONArray("scenes") ?: JSONArray()
                    (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
                        MediaScene(row.getLong("startMs"),row.getLong("endMs"),row.optString("title"),row.optString("description"),row.optString("caption"))
                    } }
                }.getOrDefault(emptyList())
            }
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


    LaunchedEffect(initialAssetId, assets) {
        if (initialAssetId.isNotBlank() && assets.any { it.id == initialAssetId }) {
            selectedAssetId = initialAssetId
            status = "已从对话打开生成视频，可预览或加入时间线。"
        }
    }

    LaunchedEffect(selectedAssetId, assets) {
        val selectedAsset = assets.firstOrNull { it.id == selectedAssetId }
        if (selectedAsset != null && selectedAsset.kind != MediaAssetKind.IMAGE) {
            val source = withContext(Dispatchers.IO) {
                runCatching { MediaProjectStore.assetFile(context, selectedAsset) }.getOrNull()
            }
            if (source != null && source.isFile) {
                assetPreviewPlayer.stop()
                assetPreviewPlayer.clearMediaItems()
                assetPreviewPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(source)))
                assetPreviewPlayer.prepare()
            }
        } else {
            assetPreviewPlayer.stop()
            assetPreviewPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(mediaAiTasks, activeMediaAiTaskId) {
        if (activeMediaAiTaskId.isBlank()) {
            val next = mediaAiTasks.firstOrNull { it.status == MediaAiTaskStatus.COMPLETED && !it.resultConsumed }?.id
                ?: mediaAiTasks.firstOrNull { it.status == MediaAiTaskStatus.QUEUED || it.status == MediaAiTaskStatus.RUNNING }?.id
            if (next != null) activeMediaAiTaskId = next
        }
    }

    LaunchedEffect(projectId, activeMediaAiTaskId) {
        if (activeMediaAiTaskId.isNotBlank()) {
            while (isActive && activeMediaAiTaskId.isNotBlank()) {
                val task = withContext(Dispatchers.IO) { MediaAiTaskStore.read(context, projectId, activeMediaAiTaskId) }
                if (task == null) {
                    status = "媒体 AI 任务记录丢失。"
                    mediaAiRunning = false
                    activeMediaAiTaskId = ""
                    break
                }
                mediaAiProgress = task.progress / 100f
                if (task.status == MediaAiTaskStatus.COMPLETED && task.resultConsumed) {
                    mediaAiTasks = withContext(Dispatchers.IO) { MediaAiTaskScheduler.list(context, projectId) }
                    activeMediaAiTaskId = ""
                    break
                }
                if (task.status == MediaAiTaskStatus.COMPLETED) {
                    val applyResult = runCatching {
                        val result = JSONObject(task.resultJson)
                        when (result.optString("type")) {
                            "transcribe" -> {
                                val rows = result.optJSONArray("cues") ?: error("转写结果缺少字幕")
                                val cues = (0 until rows.length()).map { index ->
                                    val row = rows.getJSONObject(index)
                                    MediaSubtitleCue(row.getString("id"), row.getLong("startMs"), row.getLong("endMs"), row.getString("text"))
                                }
                                val existingIds = latestTimeline.subtitles.map { it.id }.toSet()
                                val merged = (latestTimeline.subtitles + cues.filterNot { it.id in existingIds }).sortedBy { it.startMs }
                                val next = latestTimeline.copy(subtitles = merged)
                                val savedTimeline = withContext(Dispatchers.IO) { MediaProjectStore.saveTimeline(context, next, latestTimeline.revision) }
                                timeline = savedTimeline
                                status = if (cues.size == 1 && cues.firstOrNull()?.startMs == 0L) "AI 全文转写完成：生成 1 条全片字幕；如需逐句时间戳，请选择支持 segment 时间戳的转写模型。" else "AI 字幕已生成："+cues.size+" 条。"
                            }
                            "scenes" -> {
                                val rows = result.optJSONArray("scenes") ?: error("场景结果缺少 scenes")
                                detectedScenes = (0 until rows.length()).map { index ->
                                    val row = rows.getJSONObject(index)
                                    MediaScene(row.getLong("startMs"),row.getLong("endMs"),row.optString("title"),row.optString("description"),row.optString("caption"))
                                }
                                status = "场景分析完成："+detectedScenes.size+" 个场景。"
                            }
                            "audio" -> { reload(); status = "音频处理结果已作为新素材保存，原始音频未修改。" }
                            "video" -> {
                                reload()
                                status = "视频生成完成，已保存到当前项目素材库。你可以预览、加入时间线或继续让主模型修改提示词后重新生成。"
                            }
                            "image" -> { imageAnalysis = result.optString("text"); status = "图片理解完成。" }
                            else -> error("媒体 AI 任务返回未知结果类型")
                        }
                    }
                    val applyError = applyResult.exceptionOrNull()?.message.orEmpty()
                    if (applyError.isNotBlank()) status = "读取/保存媒体 AI 结果失败："+applyError
                    val refreshedTasks = withContext(Dispatchers.IO) {
                        MediaAiTaskStore.update(context = context, projectId = projectId, taskId = task.id,
                            status = MediaAiTaskStatus.COMPLETED, resultConsumed = true, errorMessage = applyError)
                        MediaAiTaskScheduler.list(context, projectId)
                    }
                    mediaAiTasks = refreshedTasks
                    mediaAiRunning = false
                    activeMediaAiTaskId = ""
                    break
                }
                if (task.status == MediaAiTaskStatus.FAILED || task.status == MediaAiTaskStatus.CANCELLED) {
                    status = if (task.status == MediaAiTaskStatus.CANCELLED) "媒体 AI 任务已取消。" else "媒体 AI 处理失败："+task.error
                    mediaAiRunning = false
                    activeMediaAiTaskId = ""
                    break
                }
                mediaAiRunning = true
                delay(500)
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

    val mediaArchiveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val archive = File(context.cacheDir, "media-project-${UUID.randomUUID()}.zip")
                    try {
                        MediaProjectStore.exportProjectArchive(context, projectId, archive)
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            archive.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("无法写入媒体项目备份")
                    } finally {
                        archive.delete()
                    }
                }
            }
            status = if (result.isSuccess) "媒体项目备份已导出，包含时间线和原始素材。" else
                "媒体项目备份失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
        }
    }

    val mediaArchiveImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingMediaArchiveUri = uri
            showMediaArchiveImportConfirm = true
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

    fun enqueueVideoGeneration() {
        val config = MediaProviderConfigStore(context).read()
        if (config == null || config.videoGenerationModel.isBlank()) {
            status = "当前媒体服务商没有配置视频生成模型。请在上方填写模型 ID 和异步任务接口路径并保存。"
            return
        }
        if (videoPromptDraft.isBlank()) {
            status = "先填写视频生成提示词。"
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaVideoGeneration.enqueue(context, projectId, config.providerId, videoPromptDraft.trim()) }
            }
            result.onSuccess { task ->
                activeMediaAiTaskId = task.id
                mediaAiRunning = true
                mediaAiProgress = 0f
                mediaAiTasks = withContext(Dispatchers.IO) { MediaAiTaskScheduler.list(context, projectId) }
                status = "视频生成任务已提交。生成过程在后台运行，完成后视频会自动保存到当前项目。"
            }.onFailure { status = "视频生成任务提交失败：" + (it.message ?: "配置无效") }
        }
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
                    val created = withContext(Dispatchers.IO) {
                        MediaAiTaskStore.create(context, projectId, asset.id, provider.providerId, aiAction,
                            "请描述图片中的主要内容、可见文字、场景与适合用于视频剪辑的画面要点。")
                    }
                    activeMediaAiTaskId = created.id
                    mediaAiRunning = true
                    mediaAiProgress = 0f
                    withContext(Dispatchers.IO) { MediaAiTaskScheduler.enqueue(context, projectId, created.id) }
                    status = "媒体 AI 任务已加入后台队列；应用关闭或进程被回收后会自动恢复。"
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
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 视频生成", style = MaterialTheme.typography.titleMedium)
                Text("主聊天模型负责理解需求和修改提示词；已配置的视频模型负责生成成品。生成结果会自动进入当前项目素材库。", style = MaterialTheme.typography.bodySmall)
                TextField(
                    value = videoPromptDraft,
                    onValueChange = { videoPromptDraft = it.take(12_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("视频生成提示词") },
                    minLines = 3
                )
                Button(
                    onClick = { enqueueVideoGeneration() },
                    enabled = !mediaAiRunning && videoPromptDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (mediaAiRunning) "媒体任务处理中…" else "生成视频") }
                Text("注意：上方通用聊天接口连通测试不代表视频生成接口已验证。首次生成会实际提交视频任务，可能产生服务商费用。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importLauncher.launch(arrayOf("video/*", "audio/*", "image/*")) }) { Text("导入素材") }
            OutlinedButton(onClick = { mediaArchiveExportLauncher.launch("nestro-media-$projectId.zip") }) { Text("备份工程") }
            OutlinedButton(onClick = { mediaArchiveImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("恢复工程") }
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
                Text("时间线预览", style = MaterialTheme.typography.labelMedium)
                val selectedPreviewAsset = assets.firstOrNull { it.id == selectedAssetId }
                if (selectedPreviewAsset != null && selectedPreviewAsset.kind != MediaAssetKind.IMAGE) {
                    AndroidView(
                        factory = { viewContext -> PlayerView(viewContext).apply { this.player = assetPreviewPlayer; useController = true } },
                        modifier = Modifier.fillMaxWidth().height(190.dp)
                    )
                    Text("素材预览：${selectedPreviewAsset.displayName}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("选择视频或音频素材即可单独预览。", style = MaterialTheme.typography.bodySmall)
                }
                Text("时间线剪辑调整不会修改原始素材。", style = MaterialTheme.typography.bodySmall)
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
        val selectedProvider = MediaIntelligenceRegistry.current()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = selected != null && selected.kind != MediaAssetKind.IMAGE && MediaIntelligenceCapability.TRANSCRIPTION in (selectedProvider?.capabilities ?: emptySet()),
                onClick = { aiAction = "transcribe"; performAiAction() }) { Text("AI 转字幕") }
            OutlinedButton(enabled = selected?.kind == MediaAssetKind.VIDEO && MediaIntelligenceCapability.SCENE_DETECTION in (selectedProvider?.capabilities ?: emptySet()),
                onClick = { aiAction = "scenes"; performAiAction() }) { Text("识别场景") }
            OutlinedButton(enabled = selected?.kind == MediaAssetKind.AUDIO && MediaIntelligenceCapability.AUDIO_PROCESSING in (selectedProvider?.capabilities ?: emptySet()),
                onClick = { aiAction = "audio-clean"; performAiAction() }) { Text("音频处理") }
            OutlinedButton(enabled = selected?.kind == MediaAssetKind.IMAGE && MediaIntelligenceCapability.IMAGE_UNDERSTANDING in (selectedProvider?.capabilities ?: emptySet()),
                onClick = { aiAction = "image"; performAiAction() }) { Text("理解图片") }
        }
        if (mediaAiRunning) {
            LinearProgressIndicator(progress = mediaAiProgress, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                val taskId = activeMediaAiTaskId
                if (taskId.isNotBlank()) scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { MediaAiTaskScheduler.cancel(context, projectId, taskId) } }
                    result.onSuccess {
                        mediaAiRunning = false
                        activeMediaAiTaskId = ""
                        status = "已取消媒体 AI 任务。"
                    }.onFailure { status = "取消未完成："+(it.message ?: "任务状态已变化") }
                }
            }) { Text("取消媒体 AI 任务") }
        }
        if (mediaAiTasks.isNotEmpty()) {
            Text("后台媒体 AI 任务", style = MaterialTheme.typography.titleSmall)
            mediaAiTasks.take(5).forEach { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(task.action+" · "+task.status.name+" · "+task.progress+"%", style = MaterialTheme.typography.bodySmall)
                        if (task.error.isNotBlank()) Text(task.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (task.status == MediaAiTaskStatus.QUEUED || task.status == MediaAiTaskStatus.RUNNING) {
                            LinearProgressIndicator(progress = task.progress / 100f, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { MediaAiTaskScheduler.pause(context, projectId, task.id) }
                                        }
                                        result.onSuccess {
                                            if (activeMediaAiTaskId == task.id) mediaAiRunning = false
                                            status = "媒体 AI 任务已暂停；可在任务列表中恢复。"
                                            mediaAiTasks = withContext(Dispatchers.IO) { MediaAiTaskScheduler.list(context, projectId) }
                                        }.onFailure { status = "暂停未完成：" + (it.message ?: "任务状态已变化") }
                                    }
                                }) { Text("暂停") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { MediaAiTaskScheduler.cancel(context, projectId, task.id) }
                                        }
                                        result.onSuccess {
                                            if (activeMediaAiTaskId == task.id) {
                                                mediaAiRunning = false
                                                activeMediaAiTaskId = ""
                                            }
                                            status = "媒体 AI 任务已取消。"
                                            mediaAiTasks = withContext(Dispatchers.IO) { MediaAiTaskScheduler.list(context, projectId) }
                                        }.onFailure { status = "取消未完成：" + (it.message ?: "任务状态已变化") }
                                    }
                                }) { Text("取消") }
                            }
                        }
                        if (task.status == MediaAiTaskStatus.PAUSED) {
                            TextButton(onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { MediaAiTaskScheduler.resume(context, projectId, task.id) }
                                    }
                                    result.onSuccess {
                                        activeMediaAiTaskId = task.id
                                        mediaAiRunning = true
                                        mediaAiProgress = 0f
                                        status = "媒体 AI 任务已恢复，将从任务入口重新执行。"
                                        mediaAiTasks = withContext(Dispatchers.IO) { MediaAiTaskScheduler.list(context, projectId) }
                                    }.onFailure { status = "恢复未完成：" + (it.message ?: "任务状态已变化") }
                                }
                            }) { Text("恢复") }
                            TextButton(onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { MediaAiTaskScheduler.cancel(context, projectId, task.id) }
                                    }
                                    result.onSuccess {
                                        status = "媒体 AI 任务已取消。"
                                        mediaAiTasks = withContext(Dispatchers.IO) { MediaAiTaskScheduler.list(context, projectId) }
                                    }.onFailure { status = "取消未完成：" + (it.message ?: "任务状态已变化") }
                                }
                            }) { Text("取消") }
                        }
                    }
                }
            }
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
                        if (result.isSuccess) {
                            val task = result.getOrThrow()
                            tasks = listOf(task) + tasks
                            status = "导出任务已登记到统一任务机并排队；暂停后恢复会从头重新渲染。"
                            MediaRenderScheduler.enqueue(context, projectId, task.id)
                        } else {
                            status = "无法创建导出任务：" + (result.exceptionOrNull()?.message ?: "未知错误")
                        }
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
                                    tasks = MediaRenderScheduler.list(context, projectId)
                                }
                            }) { Text("清理任务") }
                        }
                    }
                }
            }
        }
    }
    if (showMediaArchiveImportConfirm) {
        AlertDialog(
            onDismissRequest = {
                showMediaArchiveImportConfirm = false
                pendingMediaArchiveUri = null
            },
            title = { Text("恢复媒体工程") },
            text = {
                Text("将替换当前项目的媒体时间线、素材索引、原始素材和已导出的视频文件。当前项目的聊天、知识库和作品数据不会改变。请先确认没有未结束的渲染或媒体 AI 任务。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingMediaArchiveUri
                    showMediaArchiveImportConfirm = false
                    pendingMediaArchiveUri = null
                    if (uri != null) {
                        val hasActiveRender = tasks.any {
                            it.status in setOf(MediaRenderStatus.QUEUED, MediaRenderStatus.RUNNING, MediaRenderStatus.PAUSED)
                        }
                        val hasActiveAi = mediaAiRunning || mediaAiTasks.any {
                            it.status in setOf(MediaAiTaskStatus.QUEUED, MediaAiTaskStatus.RUNNING)
                        }
                        if (hasActiveRender || hasActiveAi) {
                            status = "当前项目仍有未结束的媒体任务，请结束或等待任务完成后再恢复。"
                        } else scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val archive = File(context.cacheDir, "media-import-${UUID.randomUUID()}.zip")
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        FileOutputStream(archive).use { output ->
                                            val buffer = ByteArray(64 * 1024)
                                            var total = 0L
                                            while (true) {
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                total += count
                                                require(total <= 2_000_000_000L) { "媒体项目备份超过 2 GB 限制" }
                                                output.write(buffer, 0, count)
                                            }
                                            output.fd.sync()
                                        }
                                    } ?: error("无法读取所选媒体项目备份")
                                    MediaProjectStore.importProjectArchive(
                                        context = context,
                                        archive = archive,
                                        targetProjectId = projectId,
                                        replaceExisting = true
                                    )
                                } finally {
                                    archive.delete()
                                }
                            }
                        }
                        result.onSuccess {
                            reload()
                            selectedAssetId = ""
                            status = "媒体工程恢复完成：时间线和原始素材已恢复。"
                        }.onFailure {
                            status = "媒体工程恢复失败：" + (it.message ?: "备份文件无效")
                        }
                    }
                    }
                }) { Text("确认替换") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMediaArchiveImportConfirm = false
                    pendingMediaArchiveUri = null
                }) { Text("取消") }
            }
        )
    }

}

private fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)



}
