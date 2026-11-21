package app.nestro.coworkbench

import android.content.Context
import app.nestro.git.GitLocalRepository
import app.nestro.git.GitProvider
import app.nestro.git.GitAccountStore
import java.net.URLEncoder
import java.io.IOException
import java.util.zip.ZipInputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object CoworkbenchReleaseGate {
    // Kept closed until all 19 modules have complete code and the user authorizes unified validation.
    const val ENABLE_REMOTE_VALIDATION = false
}

data class CoworkbenchJobStep(val name: String, val status: String, val conclusion: String?)
data class CoworkbenchArtifactFile(val path: String, val content: String)
data class CoworkbenchArtifactReport(val id: Long, val name: String, val sizeBytes: Long, val files: List<CoworkbenchArtifactFile>)
data class CoworkbenchJobResult(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val steps: List<CoworkbenchJobStep>,
    val logExcerpt: String
)

data class CoworkbenchValidationResult(
    val runId: Long,
    val status: String,
    val conclusion: String?,
    val url: String,
    val createdAt: String,
    val jobs: List<CoworkbenchJobResult> = emptyList(),
    val artifacts: List<CoworkbenchArtifactReport> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val sbomComponents: List<CoworkbenchSbomComponent> = emptyList(),
    val testReports: List<CoworkbenchTestReport> = emptyList()
) {
    val completed: Boolean get() = status == "completed"
    val successful: Boolean get() = completed && conclusion == "success"
}

/**
 * Uses GitHub-hosted Actions runners as the isolated build/test boundary.
 * It never executes repository code inside the Android process.
 */
class GitHubActionsCoworkbenchRunner(context: Context) {
    private val accounts = GitAccountStore(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun dispatchAndWait(
        repository: GitLocalRepository,
        workflowFile: String,
        branch: String,
        timeoutMinutes: Int = 20
    ): CoworkbenchValidationResult = withContext(Dispatchers.IO) {
        check(CoworkbenchReleaseGate.ENABLE_REMOTE_VALIDATION) { "统一验收尚未开放：须先完成十九个模块全部代码。" }
        require(repository.provider == GitProvider.GITHUB) { "隔离构建当前仅支持 GitHub Actions；GitLab/Gitee runner 尚未接入" }
        require(Regex("^[A-Za-z0-9_.-]{1,100}\\.ya?ml$").matches(workflowFile)) { "Workflow 必须是 .github/workflows 下的 YAML 文件名" }
        require(branch.isNotBlank() && branch.length <= 200 && branch.none { it.isISOControl() }) { "分支名称无效" }
        require(timeoutMinutes in 1..30) { "远程构建等待时间必须在 1 到 30 分钟之间" }
        val account = accounts.list().firstOrNull { it.id == repository.accountId }
            ?: error("GitHub 账号不存在，请重新授权")
        require(account.provider == GitProvider.GITHUB)
        val token = accounts.token(account.id)
        require(token.isNotBlank()) { "GitHub 授权凭据不可用" }
        val fullName = repository.fullName
        require(Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$").matches(fullName)) { "仓库名称无效" }
        val encodedWorkflow = URLEncoder.encode(workflowFile, "UTF-8")
        val encodedBranch = URLEncoder.encode(branch, "UTF-8")
        val base = "https://api.github.com/repos/" + fullName + "/actions/workflows/" + encodedWorkflow
        val startedAt = System.currentTimeMillis()
        val dispatch = Request.Builder().url(base + "/dispatches")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", "Nestro-AI-Coworkbench")
.post(JSONObject().put("ref", branch).toString().toRequestBody("application/json".toMediaType()))
            .build()
        executeCancellable(dispatch).use { response ->
            require(response.code == 204) {
                val body = response.body?.string().orEmpty().take(1200)
                when (response.code) {
                    401, 403 -> "GitHub 拒绝访问 Actions；请检查账号权限与 Actions 写入授权。"
                    404 -> "未找到该 Workflow，或它未声明 workflow_dispatch；请先推送分支并启用手动触发。"
                    else -> "触发远程隔离构建失败（HTTP " + response.code + "）：" + body
                }
            }
        }

        val deadline = startedAt + TimeUnit.MINUTES.toMillis(timeoutMinutes.toLong())
        var visibleSince: Long? = null
        while (System.currentTimeMillis() < deadline) {
            val request = Request.Builder().url(base + "/runs?branch=" + encodedBranch + "&event=workflow_dispatch&per_page=10")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "Nestro-AI-Coworkbench")
                .get().build()
            val found = executeCancellable(request).use { response ->
                val body = response.body?.string().orEmpty()
                require(response.isSuccessful) { "读取远程构建状态失败（HTTP " + response.code + "）：" + body.take(1000) }
                val runs = JSONObject(body).optJSONArray("workflow_runs") ?: org.json.JSONArray()
                (0 until runs.length()).mapNotNull { index ->
                    val row = runs.optJSONObject(index) ?: return@mapNotNull null
                    val created = row.optString("created_at")
                    val createdMs = runCatching { Instant.parse(created).toEpochMilli() }.getOrDefault(0L)
                    if (row.optString("head_branch") != branch || row.optString("event") != "workflow_dispatch" ||
                        createdMs < startedAt - 30_000L) null
                    else CoworkbenchValidationResult(
                        row.optLong("id"), row.optString("status"), row.optString("conclusion").ifBlank { null },
                        row.optString("html_url"), created
                    )
                }.maxByOrNull { it.createdAt }
            }
            if (found != null) {
                visibleSince = visibleSince ?: System.currentTimeMillis()
                if (found.completed) {
                    val diagnostics = mutableListOf<String>()
                    val jobs = try {
                        loadJobResults(fullName, token, found.runId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        diagnostics += "Job/Step 与日志读取失败：" + (error.message ?: "未知错误")
                        emptyList()
                    }
                    val artifacts = try {
                        loadArtifactReports(fullName, token, found.runId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        diagnostics += "构建产物/测试报告读取失败：" + (error.message ?: "未知错误")
                        emptyList()
                    }
                    val sbomComponents = artifacts.flatMap { artifact ->
                        artifact.files.flatMap { CoworkbenchReportParser.parseSbom(it.path, it.content) }
                    }.distinctBy { listOf(it.name, it.version, it.packageUrl) }.take(5_000)
                    val testReports = artifacts.flatMap { artifact ->
                        artifact.files.mapNotNull { CoworkbenchReportParser.parseJunitXml(it.path, it.content) }
                    }.take(100)
                    return@withContext found.copy(jobs = jobs, artifacts = artifacts, diagnostics = diagnostics,
                        sbomComponents = sbomComponents, testReports = testReports)
                }
            } else if (visibleSince == null && System.currentTimeMillis() - startedAt > 90_000L) {
                error("已触发 Workflow，但 90 秒内未找到对应运行记录；请检查 workflow_dispatch、分支和仓库 Actions 设置")
            }
            delay(5_000L)
        }
        error("远程隔离构建等待超时；任务可能仍在 GitHub Actions 运行，请到仓库 Actions 页面核对，不能据此判断失败")
    }

    private suspend fun executeCancellable(request: Request): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private suspend fun loadJobResults(
        repository: String,
        token: String,
        runId: Long
    ): List<CoworkbenchJobResult> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/" + repository + "/actions/runs/" + runId + "/jobs?per_page=100")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", "Nestro-AI-Coworkbench")
            .get().build()
        val body = executeCancellable(request).use { response ->
            require(response.isSuccessful) { "读取 Job/Step 结果失败（HTTP " + response.code + "）" }
            response.body?.string().orEmpty()
        }
        val jobs = JSONObject(body).optJSONArray("jobs") ?: org.json.JSONArray()
        return (0 until jobs.length()).mapNotNull { index ->
            val row = jobs.optJSONObject(index) ?: return@mapNotNull null
            val jobId = row.optLong("id")
            val stepsJson = row.optJSONArray("steps") ?: org.json.JSONArray()
            val steps = (0 until stepsJson.length()).mapNotNull { stepIndex ->
                val step = stepsJson.optJSONObject(stepIndex) ?: return@mapNotNull null
                CoworkbenchJobStep(
                    step.optString("name"),
                    step.optString("status"),
                    step.optString("conclusion").ifBlank { null }
                )
            }
            val logs = try {
                downloadJobLog(repository, token, jobId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "日志暂不可读取：" + (error.message ?: "未知错误")
            }
            CoworkbenchJobResult(
                jobId,
                row.optString("name"),
                row.optString("status"),
                row.optString("conclusion").ifBlank { null },
                steps,
                logs
            )
        }
    }

    private suspend fun loadArtifactReports(
        repository: String,
        token: String,
        runId: Long
    ): List<CoworkbenchArtifactReport> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/" + repository + "/actions/runs/" + runId + "/artifacts?per_page=100")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", "Nestro-AI-Coworkbench")
            .get().build()
        val body = executeCancellable(request).use { response ->
            require(response.isSuccessful) { "读取构建产物失败（HTTP " + response.code + "）" }
            response.body?.string().orEmpty()
        }
        val rows = JSONObject(body).optJSONArray("artifacts") ?: org.json.JSONArray()
        return (0 until minOf(rows.length(), 10)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (row.optBoolean("expired", false)) return@mapNotNull null
            val id = row.optLong("id")
            val name = row.optString("name")
            val size = row.optLong("size_in_bytes")
            val files = try {
                downloadArtifactFiles(repository, token, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            CoworkbenchArtifactReport(id, name, size, files)
        }
    }

    private suspend fun downloadArtifactFiles(
        repository: String,
        token: String,
        artifactId: Long
    ): List<CoworkbenchArtifactFile> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/" + repository + "/actions/artifacts/" + artifactId + "/zip")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", "Nestro-AI-Coworkbench")
            .get().build()
        return executeCancellable(request).use { response ->
            require(response.isSuccessful) { "下载构建产物失败（HTTP " + response.code + "）" }
            val stream = response.body?.byteStream() ?: return@use emptyList()
            ZipInputStream(stream).use { zip ->
                val reports = mutableListOf<CoworkbenchArtifactFile>()
                var total = 0
                var entries = 0
                while (entries < 40 && total < 2_000_000) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    val name = entry.name.replace('\\', '/')
                    val safeName = name.split('/').none { it == ".." || it.isBlank() } &&
                        !name.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(name)
                    val reportFile = listOf(".xml", ".html", ".htm", ".txt", ".json", ".log")
                        .any { name.lowercase().endsWith(it) }
                    if (!entry.isDirectory && safeName && reportFile) {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var entryBytes = 0
                        while (entryBytes < 256_000 && total < 2_000_000) {
                            val count = zip.read(buffer, 0, minOf(buffer.size, 256_000 - entryBytes, 2_000_000 - total))
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            entryBytes += count
                            total += count
                        }
                        reports += CoworkbenchArtifactFile(name, output.toString("UTF-8"))
                    }
                    zip.closeEntry()
                }
                reports
            }
        }
    }

    private suspend fun downloadJobLog(repository: String, token: String, jobId: Long): String {
        val request = Request.Builder()
            .url("https://api.github.com/repos/" + repository + "/actions/jobs/" + jobId + "/logs")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", "Nestro-AI-Coworkbench")
            .get().build()
        return executeCancellable(request).use { response ->
            require(response.isSuccessful) { "下载 Job 日志失败（HTTP " + response.code + "）" }
            val stream = response.body?.byteStream() ?: return@use ""
            ZipInputStream(stream).use { zip ->
                val output = StringBuilder()
                var total = 0
                var entries = 0
                while (entries < 20) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (!entry.isDirectory) {
                        val buffer = ByteArray(8192)
                        while (total < 1_000_000) {
                            val count = zip.read(buffer, 0, minOf(buffer.size, 1_000_000 - total))
                            if (count <= 0) break
                            output.append(String(buffer, 0, count, Charsets.UTF_8))
                            total += count
                        }
                    }
                    zip.closeEntry()
                    if (total >= 1_000_000) break
                }
                if (total >= 1_000_000) output.append("\n…日志达到 1 MB 展示上限")
                output.toString()
            }
        }
    }
}
