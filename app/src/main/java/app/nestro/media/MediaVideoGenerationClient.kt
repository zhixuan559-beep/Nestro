package app.nestro.media

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic asynchronous text-to-video adapter.
 *
 * Provider-specific job routes and response field paths are configured by the user.
 * The adapter deliberately accepts only HTTPS URLs and never follows redirects carrying
 * an API credential. Provider APIs that use a different request/response contract need
 * a dedicated adapter rather than silently guessing.
 */
class MediaVideoGenerationClient(private val config: MediaProviderConfig) {
    private val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun cancel() {
        active.forEach { runCatching { it.disconnect() } }
    }

    suspend fun generate(
        prompt: String,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        config.validate()
        require(config.videoGenerationModel.isNotBlank()) { "当前服务商尚未配置视频生成模型 ID" }
        require(prompt.isNotBlank() && prompt.length <= 12_000) { "视频提示词为空或超过 12,000 字" }
        onProgress(3)

        val submit = JSONObject()
            .put("model", config.videoGenerationModel)
            .put("prompt", prompt)
        val created = requestJson("POST", config.videoSubmitPath, submit.toString(), 30_000)
        val jobId = readPath(created, config.videoJobIdField)?.toString()?.trim().orEmpty()
        require(jobId.isNotBlank() && jobId.length <= 240 && !jobId.any { it.isISOControl() }) {
            "视频服务没有返回有效任务 ID（字段：${config.videoJobIdField}）"
        }

        var resultUrl: String? = null
        var terminalError: String? = null
        for (attempt in 0 until MAX_STATUS_POLLS) {
            val statusPath = config.videoStatusPath.replace("{job_id}", encodePathSegment(jobId))
            val statusJson = requestJson("GET", statusPath, null, 25_000)
            val state = readPath(statusJson, config.videoStatusField)?.toString()?.lowercase().orEmpty()
            resultUrl = readPath(statusJson, config.videoResultUrlField)?.toString()?.takeIf { it.isNotBlank() }
            if (state in FAILURE_STATES) {
                terminalError = readPath(statusJson, "error")?.toString()?.take(800)
                    ?: "视频生成任务失败（状态：$state）"
                break
            }
            if (state in SUCCESS_STATES && resultUrl != null) break
            onProgress((8 + ((attempt + 1).toFloat() / MAX_STATUS_POLLS * 78f)).toInt())
            if (attempt < MAX_STATUS_POLLS - 1) delay(POLL_INTERVAL_MS)
        }
        terminalError?.let { error(it) }
        val finalUrl = resultUrl ?: error("视频任务在等待时限内未返回成品地址")
        val output = downloadVideo(finalUrl)
        onProgress(100)
        output
    }

    private fun requestJson(method: String, path: String, payload: String?, timeout: Int): JSONObject {
        require(path.matches(Regex("[A-Za-z0-9._~/%{}-]{1,240}")) &&
            path.replace("{job_id}", "job-id").split('/').none { it == "." || it == ".." }) { "视频接口路径无效" }
        val url = URL(config.baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        val connection = (url.openConnection() as? HttpURLConnection) ?: error("无法建立视频服务连接")
        active.add(connection)
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = timeout
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_JSON_BYTES) { "视频服务 JSON 响应超过 2 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            require(status in 200..299) {
                "视频服务请求失败（HTTP $status）：${bytes.toString(Charsets.UTF_8).take(600)}"
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            active.remove(connection)
            connection.disconnect()
        }
    }

    private fun downloadVideo(rawUrl: String): File {
        val uri = URI(rawUrl)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawFragment == null) { "视频成品地址必须是有效 HTTPS URL" }
        val connection = (URL(rawUrl).openConnection() as? HttpURLConnection) ?: error("无法下载视频成品")
        active.add(connection)
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "video/mp4,video/*,application/octet-stream")
            val status = connection.responseCode
            require(status in 200..299) { "视频成品下载失败（HTTP $status）" }
            val mime = connection.contentType.orEmpty().substringBefore(';').lowercase()
            require(mime.startsWith("video/") || mime == "application/octet-stream") {
                "视频服务返回的成品不是视频文件（$mime）"
            }
            val declared = connection.contentLengthLong
            require(declared <= MAX_VIDEO_BYTES) { "视频成品超过 1 GB 限制" }
            val output = File.createTempFile("nestro-generated-", ".mp4")
            try {
                connection.inputStream.use { input ->
                    output.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_VIDEO_BYTES) { "视频成品超过 1 GB 限制" }
                            out.write(buffer, 0, count)
                        }
                        out.fd.sync()
                    }
                }
                require(output.isFile && output.length() > 0) { "视频服务返回空文件" }
                return output
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
        } finally {
            active.remove(connection)
            connection.disconnect()
        }
    }

    private fun readPath(root: JSONObject, path: String): Any? {
        var current: Any? = root
        path.split('.').forEach { part ->
            current = when (val value = current) {
                is JSONObject -> if (value.has(part)) value.opt(part) else null
                else -> null
            }
        }
        return current?.takeUnless { it == JSONObject.NULL }
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val MAX_STATUS_POLLS = 80
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 1_000_000_000L
        private val SUCCESS_STATES = setOf("completed", "complete", "succeeded", "success", "finished")
        private val FAILURE_STATES = setOf("failed", "error", "cancelled", "canceled", "rejected")
    }
}

object MediaVideoGeneration {
    fun enqueue(context: Context, projectId: String, providerId: String, prompt: String): MediaAiTask {
        require(prompt.isNotBlank() && prompt.length <= 12_000) { "视频提示词为空或过长" }
        val config = MediaProviderConfigStore(context).read(providerId)
            ?: error("视频服务商配置不存在或 API Key 无法读取")
        require(config.videoGenerationModel.isNotBlank()) { "请先配置视频生成模型 ID" }
        val task = MediaAiTaskStore.create(
            context = context,
            projectId = projectId,
            assetId = "",
            providerId = config.providerId,
            action = "video-generate",
            prompt = prompt
        )
        MediaAiTaskScheduler.enqueue(context, projectId, task.id)
        return task
    }
}

class MediaVideoGenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var client: MediaVideoGenerationClient? = null

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK) ?: return Result.failure()
        val task = MediaAiTaskStore.read(applicationContext, projectId, taskId) ?: return Result.failure()
        if (task.status !in setOf(MediaAiTaskStatus.QUEUED, MediaAiTaskStatus.RUNNING)) return Result.success()
        val config = MediaProviderConfigStore(applicationContext).read(task.providerId)
            ?: return fail(projectId, taskId, "视频服务商配置不存在或 API Key 无法读取")
        val activeClient = MediaVideoGenerationClient(config)
        client = activeClient
        return try {
            MediaAiTaskStore.update(applicationContext, projectId, taskId, status = MediaAiTaskStatus.RUNNING, errorMessage = "")
            val file = activeClient.generate(task.prompt) { progress ->
                runCatching { MediaAiTaskStore.update(applicationContext, projectId, taskId, progress = progress) }
                setProgressAsync(workDataOf("progress" to progress))
            }
            try {
                val imported = MediaProjectStore.importGeneratedFile(
                    applicationContext, projectId, file, MediaAssetKind.VIDEO, stableAssetId = task.id
                )
                MediaAiTaskStore.update(
                    applicationContext, projectId, taskId,
                    status = MediaAiTaskStatus.COMPLETED, progress = 100,
                    resultJson = JSONObject().put("type", "video").put("assetId", imported.id)
                        .put("displayName", imported.displayName).toString(),
                    errorMessage = ""
                )
            } finally {
                file.delete()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            runCatching { MediaAiTaskStore.update(applicationContext, projectId, taskId, status = MediaAiTaskStatus.CANCELLED) }
            throw cancelled
        } catch (error: Throwable) {
            val latest = MediaAiTaskStore.read(applicationContext, projectId, taskId)
            if (isStopped || latest?.status == MediaAiTaskStatus.CANCELLED) {
                runCatching { MediaAiTaskStore.update(applicationContext, projectId, taskId, status = MediaAiTaskStatus.CANCELLED) }
                Result.success()
            } else fail(projectId, taskId, error.message ?: error.javaClass.simpleName)
        } finally {
            client = null
            activeClient.cancel()
        }
    }

    override fun onStopped() {
        client?.cancel()
        super.onStopped()
    }

    private fun fail(projectId: String, taskId: String, message: String): Result {
        runCatching {
            MediaAiTaskStore.update(
                applicationContext, projectId, taskId,
                status = MediaAiTaskStatus.FAILED, errorMessage = message
            )
        }
        return Result.failure()
    }

    companion object {
        const val KEY_PROJECT = "media_video_project_id"
        const val KEY_TASK = "media_video_task_id"
    }
}
