package app.nestro.media

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MediaRenderTask(
    val id: String,
    val projectId: String,
    val status: MediaRenderStatus,
    val progress: Int,
    val outputFileName: String,
    val error: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

object MediaRenderTaskStore {
    private fun directory(context: Context, projectId: String): File {
        require(projectId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) { "项目 ID 无效" }
        val root = File(File(context.filesDir, "media-editor/tasks"), projectId).canonicalFile
        val base = File(context.filesDir, "media-editor/tasks").canonicalFile
        require(root.path.startsWith(base.path + File.separator)) { "任务路径越界" }
        require(root.mkdirs() || root.isDirectory) { "无法创建任务目录" }
        return root
    }

    private fun file(context: Context, projectId: String, taskId: String): File {
        require(taskId.matches(Regex("^[A-Fa-f0-9-]{36}$"))) { "任务 ID 无效" }
        return File(directory(context, projectId), taskId + ".json")
    }

    fun create(context: Context, projectId: String): MediaRenderTask {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = MediaRenderTask(
            id = id, projectId = projectId, status = MediaRenderStatus.QUEUED,
            progress = 0, outputFileName = "export-" + id + ".mp4",
            createdAt = now, updatedAt = now
        )
        write(context, task)
        return task
    }

    @Synchronized
    fun read(context: Context, projectId: String, taskId: String): MediaRenderTask? {
        val target = file(context, projectId, taskId)
        if (!target.isFile || target.length() !in 1..64_000) return null
        return runCatching {
            val json = JSONObject(target.readText(Charsets.UTF_8))
            MediaRenderTask(
                id = json.getString("id"), projectId = json.getString("projectId"),
                status = MediaRenderStatus.valueOf(json.getString("status")),
                progress = json.optInt("progress").coerceIn(0, 100),
                outputFileName = json.getString("outputFileName"),
                error = json.optString("error").take(1_000),
                createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt")
            ).also { require(it.id == taskId && it.projectId == projectId) }
        }.getOrNull()
    }

    fun list(context: Context, projectId: String): List<MediaRenderTask> =
        directory(context, projectId).listFiles { item -> item.isFile && item.extension == "json" }
            .orEmpty().mapNotNull { item ->
                val taskId = item.nameWithoutExtension
                if (!taskId.matches(Regex("^[A-Fa-f0-9-]{36}$"))) null
                else read(context, projectId, taskId) ?: MediaRenderTask(
                    id = taskId, projectId = projectId, status = MediaRenderStatus.FAILED,
                    progress = 0, outputFileName = "export-" + taskId + ".mp4",
                    error = "导出任务记录损坏，可清理此任务及其残留文件",
                    createdAt = item.lastModified(), updatedAt = item.lastModified()
                )
            }.sortedByDescending { it.createdAt }

    @Synchronized
    fun update(
        context: Context,
        projectId: String,
        taskId: String,
        status: MediaRenderStatus? = null,
        progress: Int? = null,
        errorMessage: String? = null,
        expectedStatus: MediaRenderStatus? = null
    ): MediaRenderTask {
        val current = read(context, projectId, taskId) ?: error("导出任务不存在")
        require(expectedStatus == null || current.status == expectedStatus) { "导出任务状态已变化，请刷新后重试" }
        val next = current.copy(
            status = status ?: current.status,
            progress = progress?.coerceIn(0, 100) ?: current.progress,
            error = errorMessage ?: current.error,
            updatedAt = System.currentTimeMillis()
        )
        write(context, next)
        return next
    }

    @Synchronized
    fun delete(context: Context, projectId: String, taskId: String) {
        val target = file(context, projectId, taskId)
        if (!target.exists()) return
        val current = read(context, projectId, taskId)
        if (current != null) {
            val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            val unifiedStatus = nestroApp?.agentTaskCoordinator?.state("media-render:$taskId")?.status
            val canDelete = when (unifiedStatus) {
                app.nestro.agent.AgentTaskStatus.COMPLETED,
                app.nestro.agent.AgentTaskStatus.FAILED,
                app.nestro.agent.AgentTaskStatus.CANCELLED -> true
                null -> current.status in setOf(MediaRenderStatus.COMPLETED, MediaRenderStatus.FAILED, MediaRenderStatus.CANCELLED)
                else -> false
            }
            require(canDelete) { "运行中的导出任务不能直接删除" }
        }
        val stagedTask = File(target.parentFile, "." + target.name + ".deleting")
        val output = MediaProjectStore.exportFile(context, projectId, taskId)
        val stagedOutput = File(output.parentFile, "." + output.name + ".deleting")
        require(target.renameTo(stagedTask)) { "无法暂存导出任务" }
        if (output.exists() && !output.renameTo(stagedOutput)) {
            stagedTask.renameTo(target)
            error("无法暂存导出文件")
        }
        val outputDeleted = !stagedOutput.exists() || stagedOutput.delete()
        val taskDeleted = outputDeleted && stagedTask.delete()
        if (!taskDeleted) {
            if (stagedOutput.exists()) stagedOutput.renameTo(output)
            if (stagedTask.exists()) stagedTask.renameTo(target)
            if (!output.exists()) {
                runCatching { update(context, projectId, taskId, status = MediaRenderStatus.FAILED, errorMessage = "导出文件已清理，但任务记录未能删除") }
            }
            error("无法完整清理导出任务与文件，已尝试回滚")
        }
    }

    private fun write(context: Context, task: MediaRenderTask) {
        val json = JSONObject()
            .put("id", task.id).put("projectId", task.projectId).put("status", task.status.name)
            .put("progress", task.progress).put("outputFileName", task.outputFileName)
            .put("error", task.error.take(1_000)).put("createdAt", task.createdAt).put("updatedAt", task.updatedAt)
        val target = file(context, task.projectId, task.id)
        val atomic = android.util.AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
