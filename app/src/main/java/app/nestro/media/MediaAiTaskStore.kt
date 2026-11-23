package app.nestro.media

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class MediaAiTaskStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class MediaAiTask(
    val id: String,
    val projectId: String,
    val assetId: String,
    val providerId: String,
    val action: String,
    val prompt: String,
    val status: MediaAiTaskStatus,
    val progress: Int,
    val resultJson: String = "",
    val resultConsumed: Boolean = false,
    val error: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val parentTaskId: String = "",
    val languageTag: String = "auto"
)

object MediaAiTaskStore {
    private fun directory(context: Context, projectId: String): File {
        require(projectId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) { "项目 ID 无效" }
        val base=File(context.filesDir,"media-editor/ai-tasks").canonicalFile
        val dir=File(base,projectId).canonicalFile
        require(dir.path.startsWith(base.path+File.separator)) { "AI 任务路径越界" }
        require(dir.mkdirs() || dir.isDirectory) { "无法创建 AI 任务目录" }
        return dir
    }
    private fun file(context: Context, projectId: String, taskId: String): File {
        require(taskId.matches(Regex("^[A-Fa-f0-9-]{36}$"))) { "AI 任务 ID 无效" }
        return File(directory(context,projectId),"$taskId.json")
    }
    @Synchronized fun create(context: Context, projectId: String, assetId: String, providerId: String, action: String, prompt: String = "", parentTaskId: String = "", languageTag: String = "auto"): MediaAiTask {
        require(action in setOf("transcribe","scenes","audio-clean","image","video-generate")) { "媒体 AI 操作无效" }
        require(
            (action == "video-generate" && assetId.isBlank()) ||
                (action != "video-generate" && assetId.matches(Regex("^[A-Fa-f0-9-]{36}$")))
        ) { "素材 ID 无效" }
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "服务商 ID 无效" }
        require(prompt.length <= (if (action == "video-generate") 12_000 else 2_000)) { "媒体 AI 指令过长" }
        require(languageTag == "auto" || languageTag.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"))) {
            "转写语言标识无效"
        }
        if (parentTaskId.isNotBlank()) {
            require(parentTaskId.matches(Regex("^[A-Fa-f0-9-]{36}$"))) { "上一个视频任务 ID 无效" }
            val parent = read(context, projectId, parentTaskId)
                ?: error("上一个视频任务不存在或不属于当前项目")
            require(parent.action == "video-generate") { "只能基于视频生成任务创建新版本" }
            require(parent.status == MediaAiTaskStatus.COMPLETED) { "上一版视频尚未完成，暂时不能基于它返工" }
        }
        val now=System.currentTimeMillis()
        return MediaAiTask(UUID.randomUUID().toString(),projectId,assetId,providerId,action,prompt,MediaAiTaskStatus.QUEUED,0,
            createdAt=now,updatedAt=now,parentTaskId=parentTaskId,languageTag=languageTag).also { write(context,it) }
    }
    @Synchronized fun read(context: Context, projectId: String, taskId: String): MediaAiTask? {
        val target=file(context,projectId,taskId)
        if(!target.isFile || target.length() !in 1..2_200_000) return null
        return runCatching {
            val j=JSONObject(target.readText(Charsets.UTF_8))
            MediaAiTask(j.getString("id"),j.getString("projectId"),j.getString("assetId"),j.getString("providerId"),
                j.getString("action"),j.optString("prompt"),MediaAiTaskStatus.valueOf(j.getString("status")),
                j.optInt("progress").coerceIn(0,100),j.optString("resultJson"),j.optBoolean("resultConsumed",false),j.optString("error").take(1000),
                j.getLong("createdAt"),j.getLong("updatedAt"),j.optString("parentTaskId"),j.optString("languageTag","auto")).also { require(it.id==taskId && it.projectId==projectId) }
        }.getOrNull()
    }
    fun list(context: Context, projectId: String): List<MediaAiTask> =
        directory(context,projectId).listFiles { f -> f.isFile && f.extension=="json" }.orEmpty()
            .mapNotNull { file ->
                val taskId = file.nameWithoutExtension
                if (!taskId.matches(Regex("^[A-Fa-f0-9-]{36}$"))) null
                else read(context,projectId,taskId)
            }.sortedByDescending { it.createdAt }

    @Synchronized fun update(context: Context, projectId: String, taskId: String, status: MediaAiTaskStatus?=null,
        progress: Int?=null, resultJson: String?=null, resultConsumed: Boolean?=null, errorMessage: String?=null): MediaAiTask {
        val old=read(context,projectId,taskId) ?: error("媒体 AI 任务不存在")
        require(status == null || old.status !in setOf(MediaAiTaskStatus.COMPLETED, MediaAiTaskStatus.CANCELLED) || status == old.status) {
            "终态 AI 任务不可重置"
        }
        require(resultJson==null || resultJson.length<=2_000_000) { "AI 任务结果超过 2 MB" }
        val next=old.copy(status=status ?: old.status,progress=progress?.coerceIn(0,100) ?: old.progress,
            resultJson=resultJson ?: old.resultJson,resultConsumed=resultConsumed ?: old.resultConsumed,
            error=errorMessage?.take(1000) ?: old.error,updatedAt=System.currentTimeMillis())
        write(context,next); return next
    }
    @Synchronized fun recoverable(context: Context, projectId: String): List<MediaAiTask> =
        list(context,projectId).filter { it.status==MediaAiTaskStatus.QUEUED || it.status==MediaAiTaskStatus.RUNNING }

    @Synchronized fun pendingResults(context: Context, projectId: String): List<MediaAiTask> =
        list(context,projectId).filter { it.status==MediaAiTaskStatus.COMPLETED && !it.resultConsumed }

    private fun write(context: Context, task: MediaAiTask) {
        val json=JSONObject().put("id",task.id).put("projectId",task.projectId).put("assetId",task.assetId)
            .put("providerId",task.providerId).put("action",task.action).put("prompt",task.prompt)
            .put("status",task.status.name).put("progress",task.progress).put("resultJson",task.resultJson)
            .put("resultConsumed",task.resultConsumed).put("error",task.error.take(1000)).put("createdAt",task.createdAt).put("updatedAt",task.updatedAt)
            .put("parentTaskId", task.parentTaskId).put("languageTag", task.languageTag)
        val atomic=android.util.AtomicFile(file(context,task.projectId,task.id))
        val stream=atomic.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync(); atomic.finishWrite(stream) }
        catch(t:Throwable) { atomic.failWrite(stream); throw t }
    }
}

class MediaAiTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    @Volatile private var activeProvider: OpenAiCompatibleMediaIntelligenceProvider?=null
    @Volatile private var activeVideoClient: MediaVideoGenerationClient?=null

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK) ?: return Result.failure()
        val nestroApp = applicationContext as? app.nestro.NestroApplication ?: return Result.failure()
        return try {
            nestroApp.agentTaskCoordinator.runInline(
                taskId = "media-ai:$taskId",
                owner = "media_ai",
                initialCheckpoint = "media_ai:$projectId:$taskId",
                metadata = app.nestro.agent.AgentTaskMetadata(
                    projectId = projectId,
                    kind = "media_ai",
                    capabilityId = "media.ai.run",
                    title = "媒体 AI：$taskId",
                    input = taskId
                ),
                maxRetries = 0
            ) { taskContext ->
                taskContext.checkpoint("media_ai:$projectId:$taskId")
                try {
                    val succeeded = executeMediaTask(projectId, taskId)
                    when (nestroApp.agentTaskCoordinator.state("media-ai:$taskId")?.status) {
                        app.nestro.agent.AgentTaskStatus.CANCELLED -> taskContext.cancel("媒体 AI 任务已取消")
                        app.nestro.agent.AgentTaskStatus.PAUSED -> taskContext.pause("媒体 AI 任务已暂停")
                        else -> if (succeeded) {
                            taskContext.transition(
                                app.nestro.agent.AgentTaskStatus.COMPLETED,
                                resultSummary = "媒体 AI 处理完成"
                            )
                        } else {
                            val error = MediaAiTaskStore.read(applicationContext, projectId, taskId)?.error
                                ?: "媒体 AI 任务执行失败"
                            taskContext.fail(error)
                        }
                    }
                    if (succeeded) Result.success() else Result.failure()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        when (nestroApp.agentTaskCoordinator.state("media-ai:$taskId")?.status) {
                            app.nestro.agent.AgentTaskStatus.CANCELLED -> taskContext.cancel("媒体 AI 任务已取消")
                            app.nestro.agent.AgentTaskStatus.PAUSED -> taskContext.pause("媒体 AI 任务已暂停")
                            else -> taskContext.pause("媒体 AI Worker 被系统停止，可由 WorkManager 恢复")
                        }
                    }
                    throw cancelled
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure()
        }
    }

    private suspend fun executeMediaTask(projectId: String, taskId: String): Boolean {
        val task=MediaAiTaskStore.read(applicationContext,projectId,taskId) ?: return false
        val nestroApp = applicationContext as? app.nestro.NestroApplication ?: return false
        if (nestroApp.agentTaskCoordinator.state("media-ai:$taskId")?.status != app.nestro.agent.AgentTaskStatus.RUNNING) return false
        val providerConfig=MediaProviderConfigStore(applicationContext).read(task.providerId)
            ?: return fail(projectId,taskId,"媒体服务商配置不存在或 API Key 无法读取")
        val asset: MediaAsset? = if (task.action == "video-generate") null else
            runCatching { MediaProjectStore.listAssets(applicationContext,projectId).firstOrNull { it.id==task.assetId } }.getOrNull()
        if (task.action != "video-generate" && asset == null) {
            return fail(projectId,taskId,"原始素材不存在或已损坏")
        }
        val provider=OpenAiCompatibleMediaIntelligenceProvider(providerConfig)
        activeProvider=provider
        suspend fun ensureTaskRunning() {
            val lifecycle = nestroApp.agentTaskCoordinator.state("media-ai:$taskId")?.status
            if (lifecycle != app.nestro.agent.AgentTaskStatus.RUNNING) {
                throw kotlinx.coroutines.CancellationException("媒体 AI 任务已暂停、取消或离开运行状态")
            }
        }
        return try {
            MediaAiTaskStore.update(applicationContext,projectId,taskId,errorMessage="")
            if (task.action == "video-generate") {
                val videoClient = MediaVideoGenerationClient(providerConfig)
                activeVideoClient = videoClient
                val generated = videoClient.generate(task.prompt) { value ->
                    val p = value.coerceIn(0, 100)
                    runCatching { MediaAiTaskStore.update(applicationContext,projectId,taskId,progress=p) }
                    setProgressAsync(workDataOf("progress" to p))
                }
                try {
                    ensureTaskRunning()
                    val imported = MediaProjectStore.importGeneratedFile(
                        applicationContext, projectId, generated, MediaAssetKind.VIDEO, stableAssetId = task.id
                    )
                    val result = JSONObject().put("type","video").put("assetId",imported.id)
                        .put("displayName",imported.displayName).toString()
                    MediaAiTaskStore.update(applicationContext,projectId,taskId,
                        progress=100,resultJson=result,errorMessage="")
                    return true
                } finally {
                    generated.delete()
                    activeVideoClient = null
                    videoClient.cancel()
                }
            }
            val mediaAsset = asset ?: error("媒体素材不存在")
            val file=MediaProjectStore.assetFile(applicationContext,mediaAsset)
            val progress:(Float)->Unit={ value ->
                val p=(value.coerceIn(0f,1f)*100).toInt()
                runCatching { MediaAiTaskStore.update(applicationContext,projectId,taskId,progress=p) }
                setProgressAsync(workDataOf("progress" to p))
            }
            val result=withContext(Dispatchers.IO) {
                when(task.action) {
                    "transcribe" -> {
                        val cues=provider.transcribe(mediaAsset,file,task.languageTag,progress)
                        JSONObject().put("type","transcribe").put("cues",JSONArray().apply {
                            cues.forEach { put(JSONObject().put("id",it.id).put("startMs",it.startMs).put("endMs",it.endMs).put("text",it.text)) }
                        }).toString()
                    }
                    "scenes" -> {
                        val scenes=provider.detectScenes(mediaAsset,file,progress)
                        JSONObject().put("type","scenes").put("scenes",JSONArray().apply {
                            scenes.forEach { put(JSONObject().put("startMs",it.startMs).put("endMs",it.endMs).put("title",it.title).put("description",it.description).put("caption",it.suggestedCaption)) }
                        }).toString()
                    }
                    "audio-clean" -> {
                        val output=provider.processAudio(mediaAsset,file,"noise_reduction",progress)
                        try {
                            ensureTaskRunning()
                            val imported=MediaProjectStore.importGeneratedFile(applicationContext,projectId,output,MediaAssetKind.AUDIO,stableAssetId=task.id)
                            JSONObject().put("type","audio").put("assetId",imported.id).put("displayName",imported.displayName).toString()
                        } finally { output.delete() }
                    }
                    "image" -> JSONObject().put("type","image").put("text",provider.analyzeImage(mediaAsset,file,task.prompt.ifBlank { "请描述图片中的主要内容、可见文字、场景与适合用于视频剪辑的画面要点。" },progress)).toString()
                    else -> error("不支持的媒体 AI 操作")
                }
            }
            ensureTaskRunning()
            MediaAiTaskStore.update(applicationContext,projectId,taskId,progress=100,resultJson=result,errorMessage="")
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Explicit cancellation is persisted by the scheduler; system stops remain resumable.
            throw cancelled
        } catch(t:Throwable) {
            when {
                nestroApp.agentTaskCoordinator.state("media-ai:$taskId")?.status in setOf(
                    app.nestro.agent.AgentTaskStatus.CANCELLED,
                    app.nestro.agent.AgentTaskStatus.PAUSED
                ) -> true
                isStopped -> {
                    val stopped = kotlinx.coroutines.CancellationException("媒体 AI Worker 被系统停止")
                    stopped.initCause(t)
                    throw stopped
                }
                else -> fail(projectId,taskId,t.message ?: t.javaClass.simpleName)
            }
        } finally {
            activeProvider=null
            activeVideoClient?.cancel()
            activeVideoClient=null
            provider.cancelActiveRequests()
        }
    }

    private fun fail(projectId:String,taskId:String,message:String):Boolean {
        runCatching { MediaAiTaskStore.update(applicationContext,projectId,taskId,errorMessage=message) }
        return false
    }

    companion object {
        const val KEY_PROJECT="media_ai_project_id"
        const val KEY_TASK="media_ai_task_id"
    }
}

object MediaAiTaskScheduler {
    private fun unique(projectId:String,taskId:String)="media-ai-$projectId-$taskId"

    private suspend fun registerUnifiedTask(context: Context, projectId: String, taskId: String): app.nestro.NestroApplication {
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            ?: error("媒体任务无法连接统一任务机")
        val task = MediaAiTaskStore.read(context, projectId, taskId)
            ?: error("媒体 AI 任务不存在")
        nestroApp.agentTaskCoordinator.registerInlineTask(
            taskId = "media-ai:$taskId",
            owner = "media_ai",
            checkpoint = "media_ai:$projectId:$taskId",
            metadata = app.nestro.agent.AgentTaskMetadata(
                projectId = projectId,
                kind = "media_ai",
                capabilityId = "media.ai.run",
                title = "媒体 AI：${task.action}",
                input = taskId
            )
        )
        return nestroApp
    }

    suspend fun enqueue(context:Context,projectId:String,taskId:String,replace:Boolean=false) {
        registerUnifiedTask(context, projectId, taskId)
        val request=OneTimeWorkRequestBuilder<MediaAiTaskWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(MediaAiTaskWorker.KEY_PROJECT to projectId,MediaAiTaskWorker.KEY_TASK to taskId)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            unique(projectId,taskId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    suspend fun read(context: Context, projectId: String, taskId: String): MediaAiTask? {
        val task = MediaAiTaskStore.read(context, projectId, taskId) ?: return null
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication ?: return task
        val unified = nestroApp.agentTaskCoordinator.state("media-ai:$taskId")
        val status = when (unified?.status) {
            app.nestro.agent.AgentTaskStatus.CREATED,
            app.nestro.agent.AgentTaskStatus.QUEUED,
            app.nestro.agent.AgentTaskStatus.RETRY_WAIT -> MediaAiTaskStatus.QUEUED
            app.nestro.agent.AgentTaskStatus.RUNNING -> MediaAiTaskStatus.RUNNING
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK,
            app.nestro.agent.AgentTaskStatus.PAUSED -> MediaAiTaskStatus.PAUSED
            app.nestro.agent.AgentTaskStatus.COMPLETED -> MediaAiTaskStatus.COMPLETED
            app.nestro.agent.AgentTaskStatus.FAILED -> MediaAiTaskStatus.FAILED
            app.nestro.agent.AgentTaskStatus.CANCELLED -> MediaAiTaskStatus.CANCELLED
            null -> task.status
        }
        return task.copy(status = status, error = unified?.lastError?.ifBlank { task.error } ?: task.error)
    }

    suspend fun list(context: Context, projectId: String): List<MediaAiTask> {
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            ?: return MediaAiTaskStore.list(context, projectId)
        return MediaAiTaskStore.list(context, projectId).map { task ->
            val unified = nestroApp.agentTaskCoordinator.state("media-ai:${task.id}")
            val status = when (unified?.status) {
                app.nestro.agent.AgentTaskStatus.CREATED,
                app.nestro.agent.AgentTaskStatus.QUEUED,
                app.nestro.agent.AgentTaskStatus.RETRY_WAIT -> MediaAiTaskStatus.QUEUED
                app.nestro.agent.AgentTaskStatus.RUNNING -> MediaAiTaskStatus.RUNNING
                app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
                app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK,
                app.nestro.agent.AgentTaskStatus.PAUSED -> MediaAiTaskStatus.PAUSED
                app.nestro.agent.AgentTaskStatus.COMPLETED -> MediaAiTaskStatus.COMPLETED
                app.nestro.agent.AgentTaskStatus.FAILED -> MediaAiTaskStatus.FAILED
                app.nestro.agent.AgentTaskStatus.CANCELLED -> MediaAiTaskStatus.CANCELLED
                null -> task.status
            }
            task.copy(status = status)
        }
    }

    suspend fun recover(context:Context,projectId:String) {
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            ?: error("媒体 AI 任务无法连接统一任务机")
        MediaAiTaskStore.list(context, projectId).forEach { task ->
            val unified = nestroApp.agentTaskCoordinator.state("media-ai:${task.id}")
            val shouldWake = when (unified?.status) {
                null -> task.status in setOf(MediaAiTaskStatus.QUEUED, MediaAiTaskStatus.RUNNING)
                app.nestro.agent.AgentTaskStatus.CREATED,
                app.nestro.agent.AgentTaskStatus.QUEUED,
                app.nestro.agent.AgentTaskStatus.RUNNING,
                app.nestro.agent.AgentTaskStatus.RETRY_WAIT -> true
                else -> false
            }
            if (shouldWake) enqueue(context, projectId, task.id)
        }
    }

    suspend fun pause(context: Context, projectId: String, taskId: String) {
        val nestroApp = registerUnifiedTask(context, projectId, taskId)
        val state = nestroApp.agentTaskCoordinator.state("media-ai:$taskId")
            ?: error("统一媒体 AI 任务状态不存在")
        require(state.status in setOf(
            app.nestro.agent.AgentTaskStatus.CREATED,
            app.nestro.agent.AgentTaskStatus.QUEUED,
            app.nestro.agent.AgentTaskStatus.RUNNING,
            app.nestro.agent.AgentTaskStatus.RETRY_WAIT
        )) { "当前统一媒体 AI 任务不能暂停：${state.status}" }
        nestroApp.agentTaskCoordinator.pause("media-ai:$taskId")
        WorkManager.getInstance(context).cancelUniqueWork(unique(projectId, taskId))
    }

    suspend fun resume(context: Context, projectId: String, taskId: String) {
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            ?: error("媒体 AI 任务无法连接统一任务机")
        val state = nestroApp.agentTaskCoordinator.state("media-ai:$taskId")
            ?: error("统一媒体 AI 任务状态不存在")
        require(state.status in setOf(
            app.nestro.agent.AgentTaskStatus.PAUSED,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK
        )) { "只有统一任务机中的暂停/等待任务可以恢复" }
        MediaAiTaskStore.update(
            context, projectId, taskId,
            progress = 0,
            errorMessage = ""
        )
        enqueue(context, projectId, taskId, replace = true)
    }

    suspend fun cancel(context:Context,projectId:String,taskId:String) {
        val nestroApp = registerUnifiedTask(context, projectId, taskId)
        val state = nestroApp.agentTaskCoordinator.state("media-ai:$taskId")
            ?: error("统一媒体 AI 任务状态不存在")
        require(state.status in setOf(
            app.nestro.agent.AgentTaskStatus.CREATED,
            app.nestro.agent.AgentTaskStatus.QUEUED,
            app.nestro.agent.AgentTaskStatus.RUNNING,
            app.nestro.agent.AgentTaskStatus.RETRY_WAIT,
            app.nestro.agent.AgentTaskStatus.PAUSED,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK
        )) { "当前统一媒体 AI 任务不可取消" }
        nestroApp.agentTaskCoordinator.cancel("media-ai:$taskId")
        WorkManager.getInstance(context).cancelUniqueWork(unique(projectId,taskId))
    }
}
