package app.nestro.coworkbench

import android.content.Context
import app.nestro.git.GitLocalRepository
import app.nestro.git.GitProvider
import app.nestro.git.GitAccountStore
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object CoworkbenchReleaseGate {
    // Kept closed until all 19 modules have complete code and the user authorizes unified validation.
    const val ENABLE_REMOTE_VALIDATION = false
}

data class CoworkbenchValidationResult(
    val runId: Long,
    val status: String,
    val conclusion: String?,
    val url: String,
    val createdAt: String
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
            .post("{\"ref\":\"" + JSONObject.quote(branch).removeSurrounding("\"", "\"") + "\"}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(dispatch).execute().use { response ->
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
            val found = client.newCall(request).execute().use { response ->
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
                if (found.completed) return@withContext found
            } else if (visibleSince == null && System.currentTimeMillis() - startedAt > 90_000L) {
                error("已触发 Workflow，但 90 秒内未找到对应运行记录；请检查 workflow_dispatch、分支和仓库 Actions 设置")
            }
            delay(5_000L)
        }
        error("远程隔离构建等待超时；任务可能仍在 GitHub Actions 运行，请到仓库 Actions 页面核对，不能据此判断失败")
    }
}
