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

enum class MediaAiTaskStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

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
    val updatedAt: Long
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
    @Synchronized fun create(context: Context, projectId: String, assetId: String, providerId: String, action: String, prompt: String = ""): MediaAiTask {
        require(action in setOf("transcribe","scenes","audio-clean","image","video-generate")) { "媒体 AI 操作无效" }
        require(
            (action == "video-generate" && assetId.isBlank()) ||
                (action != "video-generate" && assetId.matches(Regex("^[A-Fa-f0-9-]{36}$")))
        ) { "素材 ID 无效" }
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "服务商 ID 无效" }
        require(prompt.length<=2000) { "媒体 AI 指令过长" }
        val now=System.currentTimeMillis()
        return MediaAiTask(UUID.randomUUID().toString(),projectId,assetId,providerId,action,prompt,MediaAiTaskStatus.QUEUED,0,createdAt=now,updatedAt=now).also { write(context,it) }
    }
    @Synchronized fun read(context: Context, projectId: String, taskId: String): MediaAiTask? {
        val target=file(context,projectId,taskId)
        if(!target.isFile || target.length() !in 1..2_200_000) return null
        return runCatching {
            val j=JSONObject(target.readText(Charsets.UTF_8))
            MediaAiTask(j.getString("id"),j.getString("projectId"),j.getString("assetId"),j.getString("providerId"),
                j.getString("action"),j.optString("prompt"),MediaAiTaskStatus.valueOf(j.getString("status")),
                j.optInt("progress").coerceIn(0,100),j.optString("resultJson"),j.optBoolean("resultConsumed",false),j.optString("error").take(1000),
                j.getLong("createdAt"),j.getLong("updatedAt")).also { require(it.id==taskId && it.projectId==projectId) }
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
        require(old.status !in setOf(MediaAiTaskStatus.COMPLETED,MediaAiTaskStatus.CANCELLED) || status==old.status) { "终态 AI 任务不可覆盖" }
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
        val projectId=inputData.getString(KEY_PROJECT) ?: return Result.failure()
        val taskId=inputData.getString(KEY_TASK) ?: return Result.failure()
        val task=MediaAiTaskStore.read(applicationContext,projectId,taskId) ?: return Result.failure()
        if(task.status !in setOf(MediaAiTaskStatus.QUEUED,MediaAiTaskStatus.RUNNING)) return Result.success()
        val providerConfig=MediaProviderConfigStore(applicationContext).read(task.providerId)
            ?: return fail(projectId,taskId,"媒体服务商配置不存在或 API Key 无法读取")
        val asset = if (task.action == "video-generate") null else
            runCatching { MediaProjectStore.listAssets(applicationContext,projectId).firstOrNull { it.id==task.assetId } }.getOrNull()
                ?: return fail(projectId,taskId,"原始素材不存在或已损坏")
        val provider=OpenAiCompatibleMediaIntelligenceProvider(providerConfig)
        activeProvider=provider
        return try {
            MediaAiTaskStore.update(applicationContext,projectId,taskId,status=MediaAiTaskStatus.RUNNING,errorMessage="")
            if (task.action == "video-generate") {
                val videoClient = MediaVideoGenerationClient(providerConfig)
                activeVideoClient = videoClient
                val generated = videoClient.generate(task.prompt) { value ->
                    val p = value.coerceIn(0, 100)
                    runCatching { MediaAiTaskStore.update(applicationContext,projectId,taskId,progress=p) }
                    setProgressAsync(workDataOf("progress" to p))
                }
                try {
                    val imported = MediaProjectStore.importGeneratedFile(
                        applicationContext, projectId, generated, MediaAssetKind.VIDEO, stableAssetId = task.id
                    )
                    val result = JSONObject().put("type","video").put("assetId",imported.id)
                        .put("displayName",imported.displayName).toString()
                    MediaAiTaskStore.update(applicationContext,projectId,taskId,status=MediaAiTaskStatus.COMPLETED,
                        progress=100,resultJson=result,errorMessage="")
                    return Result.success()
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
                        val cues=provider.transcribe(mediaAsset,file,"zh-CN",progress)
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
                            val imported=MediaProjectStore.importGeneratedFile(applicationContext,projectId,output,MediaAssetKind.AUDIO,stableAssetId=task.id)
                            JSONObject().put("type","audio").put("assetId",imported.id).put("displayName",imported.displayName).toString()
                        } finally { output.delete() }
                    }
                    "image" -> JSONObject().put("type","image").put("text",provider.analyzeImage(mediaAsset,file,task.prompt.ifBlank { "请描述图片中的主要内容、可见文字、场景与适合用于视频剪辑的画面要点。" },progress)).toString()
                    else -> error("不支持的媒体 AI 操作")
                }
            }
            MediaAiTaskStore.update(applicationContext,projectId,taskId,status=MediaAiTaskStatus.COMPLETED,progress=100,resultJson=result,errorMessage="")
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (isStopped) {
                runCatching { MediaAiTaskStore.update(applicationContext,projectId,taskId,status=MediaAiTaskStatus.CANCELLED) }
            }
            throw cancelled
        } catch(t:Throwable) {
            val latest=MediaAiTaskStore.read(applicationContext,projectId,taskId)
            if(isStopped || latest?.status==MediaAiTaskStatus.CANCELLED) {
                runCatching { MediaAiTaskStore.update(applicationContext,projectId,taskId,status=MediaAiTaskStatus.CANCELLED) }
                Result.success()
            } else fail(projectId,taskId,t.message ?: t.javaClass.simpleName)
        } finally {
            activeProvider=null
            activeVideoClient?.cancel()
            activeVideoClient=null
            provider.cancelActiveRequests()
        }
    }

    override fun onStopped() {
        activeProvider?.cancelActiveRequests()
        activeVideoClient?.cancel()
        super.onStopped()
    }

    private fun fail(projectId:String,taskId:String,message:String):Result {
        runCatching { MediaAiTaskStore.update(applicationContext,projectId,taskId,status=MediaAiTaskStatus.FAILED,errorMessage=message) }
        return Result.failure()
    }

    companion object {
        const val KEY_PROJECT="media_ai_project_id"
        const val KEY_TASK="media_ai_task_id"
    }
}

object MediaAiTaskScheduler {
    private fun unique(projectId:String,taskId:String)="media-ai-$projectId-$taskId"
    fun enqueue(context:Context,projectId:String,taskId:String) {
        val request=OneTimeWorkRequestBuilder<MediaAiTaskWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(MediaAiTaskWorker.KEY_PROJECT to projectId,MediaAiTaskWorker.KEY_TASK to taskId)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(unique(projectId,taskId),ExistingWorkPolicy.KEEP,request)
    }
    fun recover(context:Context,projectId:String) {
        MediaAiTaskStore.recoverable(context,projectId).forEach { enqueue(context,projectId,it.id) }
    }
    fun cancel(context:Context,projectId:String,taskId:String) {
        val task=MediaAiTaskStore.read(context,projectId,taskId) ?: return
        require(task.status in setOf(MediaAiTaskStatus.QUEUED,MediaAiTaskStatus.RUNNING)) { "当前媒体 AI 任务不可取消" }
        MediaAiTaskStore.update(context,projectId,taskId,status=MediaAiTaskStatus.CANCELLED)
        WorkManager.getInstance(context).cancelUniqueWork(unique(projectId,taskId))
    }
}
