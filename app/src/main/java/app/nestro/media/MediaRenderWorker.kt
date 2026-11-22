package app.nestro.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class MediaRenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val nestroApp = applicationContext as? app.nestro.NestroApplication ?: return Result.failure()
        return try {
            nestroApp.agentTaskCoordinator.runInline(
                taskId = "media-render:$taskId",
                owner = "media_render",
                initialCheckpoint = "media_render:$projectId:$taskId",
                metadata = app.nestro.agent.AgentTaskMetadata(
                    projectId = projectId,
                    kind = "media_render",
                    capabilityId = "media.render.export",
                    title = "媒体渲染：$taskId",
                    input = taskId
                ),
                maxRetries = 0
            ) { taskContext ->
                taskContext.checkpoint("media_render:$projectId:$taskId")
                try {
                    val result = executeRenderTask(projectId, taskId)
                    when (MediaRenderTaskStore.read(applicationContext, projectId, taskId)?.status) {
                        MediaRenderStatus.COMPLETED ->
                            taskContext.transition(app.nestro.agent.AgentTaskStatus.COMPLETED, resultSummary = "媒体渲染导出完成")
                        MediaRenderStatus.CANCELLED ->
                            taskContext.cancel("媒体渲染任务已取消")
                        MediaRenderStatus.FAILED ->
                            taskContext.fail(MediaRenderTaskStore.read(applicationContext, projectId, taskId)?.error ?: "媒体渲染失败")
                        null -> taskContext.fail("媒体渲染任务记录丢失，无法继续执行")
                        MediaRenderStatus.PAUSED ->
                            taskContext.pause("媒体渲染任务已暂停")
                        else -> taskContext.pause("媒体渲染 Worker 未完成，等待恢复")
                    }
                    result
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        if (MediaRenderTaskStore.read(applicationContext, projectId, taskId)?.status == MediaRenderStatus.CANCELLED) {
                            taskContext.cancel("媒体渲染任务已取消")
                        } else {
                            taskContext.pause("媒体渲染 Worker 被系统停止或任务已暂停")
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

    private suspend fun executeRenderTask(projectId: String, taskId: String): Result {
        val task = MediaRenderTaskStore.read(applicationContext, projectId, taskId) ?: return Result.failure()
        if (task.status !in setOf(MediaRenderStatus.QUEUED, MediaRenderStatus.RUNNING)) return Result.success()
        setForeground(createForegroundInfo(projectId, taskId, task.progress))
        val output = MediaProjectStore.exportFile(applicationContext, projectId, taskId)
        val started = runCatching {
            MediaRenderTaskStore.update(
                applicationContext, projectId, taskId,
                status = MediaRenderStatus.RUNNING, progress = 0, errorMessage = "",
                expectedStatus = task.status
            )
        }
        if (started.isFailure) {
            val latest = MediaRenderTaskStore.read(applicationContext, projectId, taskId)
            return if (latest?.status == MediaRenderStatus.PAUSED || latest?.status == MediaRenderStatus.CANCELLED) Result.success() else Result.failure()
        }
        if (output.exists() && !output.delete()) {
            MediaRenderTaskStore.update(applicationContext, projectId, taskId, status = MediaRenderStatus.FAILED, errorMessage = "无法清理上一次未完成的导出文件", expectedStatus = MediaRenderStatus.RUNNING)
            return Result.failure()
        }

        val completion = CompletableDeferred<Throwable?>()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                completion.complete(null)
            }

            override fun onError(
                composition: androidx.media3.transformer.Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                completion.complete(exportException)
            }
        }
        var transformer: Transformer? = null
        try {
            val composition = withContext(Dispatchers.IO) {
                MediaRenderEngine.buildComposition(applicationContext, projectId)
            }
            val activeTransformer = withContext(Dispatchers.Main.immediate) {
                Transformer.Builder(applicationContext).addListener(listener).build().also {
                    it.start(composition, output.absolutePath)
                }
            }
            transformer = activeTransformer
            while (!completion.isCompleted) {
                val latest = withContext(Dispatchers.IO) {
                    MediaRenderTaskStore.read(applicationContext, projectId, taskId)
                } ?: error("导出任务状态丢失")
                if (latest.status == MediaRenderStatus.PAUSED || latest.status == MediaRenderStatus.CANCELLED) {
                    withContext(Dispatchers.Main.immediate) { activeTransformer.cancel() }
                    output.delete()
                    if (latest.status == MediaRenderStatus.PAUSED) {
                        MediaRenderTaskStore.update(applicationContext, projectId, taskId, progress = 0)
                    }
                    return Result.success()
                }
                val holder = ProgressHolder()
                val state = withContext(Dispatchers.Main.immediate) { activeTransformer.getProgress(holder) }
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    MediaRenderTaskStore.update(applicationContext, projectId, taskId, progress = holder.progress, expectedStatus = MediaRenderStatus.RUNNING)
                    setProgress(workDataOf("progress" to holder.progress))
                    setForeground(createForegroundInfo(projectId, taskId, holder.progress))
                }
                delay(500)
            }
            val failure = completion.await()
            if (failure != null) throw failure
            val finalTask = MediaRenderTaskStore.read(applicationContext, projectId, taskId) ?: error("导出任务状态丢失")
            if (finalTask.status == MediaRenderStatus.CANCELLED || finalTask.status == MediaRenderStatus.PAUSED) {
                output.delete()
                return Result.success()
            }
            require(finalTask.status == MediaRenderStatus.RUNNING) { "导出任务状态已变化，拒绝覆盖新状态" }
            require(output.isFile && output.length() > 0) { "导出完成回调已触发，但没有生成有效文件" }
            MediaRenderTaskStore.update(applicationContext, projectId, taskId, status = MediaRenderStatus.COMPLETED, progress = 100, expectedStatus = MediaRenderStatus.RUNNING)
            return Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { withContext(Dispatchers.Main.immediate) { transformer?.cancel() } }
                val latest = MediaRenderTaskStore.read(applicationContext, projectId, taskId)
                if (latest?.status == MediaRenderStatus.PAUSED || latest?.status == MediaRenderStatus.CANCELLED) {
                    output.delete()
                    if (latest.status == MediaRenderStatus.PAUSED) {
                        runCatching { MediaRenderTaskStore.update(applicationContext, projectId, taskId, progress = 0) }
                    }
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            val latest = MediaRenderTaskStore.read(applicationContext, projectId, taskId)
            if (latest?.status == MediaRenderStatus.CANCELLED || latest?.status == MediaRenderStatus.PAUSED) {
                output.delete()
                return Result.success()
            }
            output.delete()
            runCatching {
                MediaRenderTaskStore.update(
                    applicationContext, projectId, taskId,
                    status = MediaRenderStatus.FAILED,
                    errorMessage = failure.message ?: failure.javaClass.simpleName,
                    expectedStatus = MediaRenderStatus.RUNNING
                )
            }
            return Result.failure()
        }
    }

    private fun createForegroundInfo(projectId: String, taskId: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "媒体导出", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Nestro 视频导出")
            .setContentText("项目 " + projectId + " · 导出中 " + progress + "%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 ->
                ForegroundInfo(taskId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 34 ->
                ForegroundInfo(taskId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else -> ForegroundInfo(taskId.hashCode(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "nestro_media_render"
        const val KEY_PROJECT_ID = "media_project_id"
        const val KEY_TASK_ID = "media_task_id"
    }
}

object MediaRenderScheduler {
    private fun uniqueName(projectId: String, taskId: String) = "media-render-" + projectId + "-" + taskId

    suspend fun list(context: Context, projectId: String): List<MediaRenderTask> {
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            ?: return MediaRenderTaskStore.list(context, projectId)
        return MediaRenderTaskStore.list(context, projectId).map { task ->
            val unified = nestroApp.agentTaskCoordinator.state("media-render:${task.id}")
            val status = when (unified?.status) {
                app.nestro.agent.AgentTaskStatus.CREATED,
                app.nestro.agent.AgentTaskStatus.QUEUED,
                app.nestro.agent.AgentTaskStatus.RETRY_WAIT -> MediaRenderStatus.QUEUED
                app.nestro.agent.AgentTaskStatus.RUNNING -> MediaRenderStatus.RUNNING
                app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
                app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK,
                app.nestro.agent.AgentTaskStatus.PAUSED -> MediaRenderStatus.PAUSED
                app.nestro.agent.AgentTaskStatus.COMPLETED -> MediaRenderStatus.COMPLETED
                app.nestro.agent.AgentTaskStatus.FAILED -> MediaRenderStatus.FAILED
                app.nestro.agent.AgentTaskStatus.CANCELLED -> MediaRenderStatus.CANCELLED
                null -> task.status
            }
            task.copy(status = status)
        }
    }

    private suspend fun registerUnifiedTask(context: Context, projectId: String, taskId: String): app.nestro.NestroApplication {
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            ?: error("媒体渲染任务无法连接统一任务机")
        val task = MediaRenderTaskStore.read(context, projectId, taskId)
            ?: error("媒体渲染任务不存在")
        nestroApp.agentTaskCoordinator.registerInlineTask(
            taskId = "media-render:$taskId",
            owner = "media_render",
            checkpoint = "media_render:$projectId:$taskId",
            metadata = app.nestro.agent.AgentTaskMetadata(
                projectId = projectId,
                kind = "media_render",
                capabilityId = "media.render.export",
                title = "媒体渲染导出",
                input = taskId
            )
        )
        return nestroApp
    }

    suspend fun enqueue(context: Context, projectId: String, taskId: String, replace: Boolean = false) {
        registerUnifiedTask(context, projectId, taskId)
        val request = OneTimeWorkRequestBuilder<MediaRenderWorker>()
            .setInputData(workDataOf(
                MediaRenderWorker.KEY_PROJECT_ID to projectId,
                MediaRenderWorker.KEY_TASK_ID to taskId
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(projectId, taskId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    suspend fun pause(context: Context, projectId: String, taskId: String) {
        val nestroApp = registerUnifiedTask(context, projectId, taskId)
        val state = nestroApp.agentTaskCoordinator.state("media-render:$taskId")
            ?: error("统一媒体渲染任务状态不存在")
        require(state.status in setOf(
            app.nestro.agent.AgentTaskStatus.CREATED,
            app.nestro.agent.AgentTaskStatus.QUEUED,
            app.nestro.agent.AgentTaskStatus.RUNNING,
            app.nestro.agent.AgentTaskStatus.RETRY_WAIT
        )) { "当前统一媒体渲染任务不能暂停：${state.status}" }
        MediaRenderTaskStore.update(context, projectId, taskId, status = MediaRenderStatus.PAUSED)
        nestroApp.agentTaskCoordinator.pause("media-render:$taskId")
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId, taskId))
    }

    suspend fun resume(context: Context, projectId: String, taskId: String) {
        val nestroApp = context.applicationContext as? app.nestro.NestroApplication
            ?: error("媒体渲染任务无法连接统一任务机")
        val state = nestroApp.agentTaskCoordinator.state("media-render:$taskId")
            ?: error("统一媒体渲染任务状态不存在")
        require(state.status in setOf(
            app.nestro.agent.AgentTaskStatus.PAUSED,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK
        )) { "只有统一任务机中的暂停/等待任务可以恢复" }
        MediaRenderTaskStore.update(
            context, projectId, taskId,
            status = MediaRenderStatus.QUEUED,
            progress = 0,
            errorMessage = ""
        )
        enqueue(context, projectId, taskId, replace = true)
    }

    suspend fun cancel(context: Context, projectId: String, taskId: String) {
        val nestroApp = registerUnifiedTask(context, projectId, taskId)
        val state = nestroApp.agentTaskCoordinator.state("media-render:$taskId")
            ?: error("统一媒体渲染任务状态不存在")
        require(state.status in setOf(
            app.nestro.agent.AgentTaskStatus.CREATED,
            app.nestro.agent.AgentTaskStatus.QUEUED,
            app.nestro.agent.AgentTaskStatus.RUNNING,
            app.nestro.agent.AgentTaskStatus.RETRY_WAIT,
            app.nestro.agent.AgentTaskStatus.PAUSED,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_USER,
            app.nestro.agent.AgentTaskStatus.WAITING_FOR_TASK
        )) { "当前统一媒体渲染任务不可取消" }
        MediaRenderTaskStore.update(context, projectId, taskId, status = MediaRenderStatus.CANCELLED)
        nestroApp.agentTaskCoordinator.cancel("media-render:$taskId")
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId, taskId))
    }
}
