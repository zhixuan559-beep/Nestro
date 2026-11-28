package app.nestro.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 改版第 c 步：自动备份 Worker——每天一次把本地备份写进应用私有目录的滚动文件。
 * 开关在资料页（一个开关，全自动）；备份内容与手动导出同一份 JSON（buildLocalBackup），
 * API Key 不进备份；只有一个滚动文件，不无限膨胀；失败不重试到天荒地老，下个周期再试。
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appContext = applicationContext
        return try {
            val prefs = appContext.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            val database = NestroDatabase.get(appContext)
            val json = buildLocalBackup(appContext, prefs, database)
            withContext(Dispatchers.IO) {
                val dir = File(appContext.filesDir, "auto-backup").apply { mkdirs() }
                File(dir, "Nestro-auto-backup.json").writeText(json)
            }
            prefs.edit().putLong(AutoBackupScheduler.LAST_RUN_KEY, System.currentTimeMillis()).apply()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure()
        }
    }
}

object AutoBackupScheduler {
    const val UNIQUE_WORK_NAME = "nestro-auto-backup"
    const val PREF_KEY = "auto_backup_v1"
    const val LAST_RUN_KEY = "auto_backup_last_run_v1"
    const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, DEFAULT_ENABLED)

    /** 开关切换与应用启动时都走这里：开则排每日一次的周期任务，关则取消。 */
    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (isEnabled(context)) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
