package app.nestro.coworkbench

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.nestro.git.GitWorkbenchRepository

data class CoworkbenchProposal(val path: String, val content: String?)
data class CoworkbenchFileReview(val path: String, val before: String?, val after: String?, val diff: String, val impact: List<String>)
data class CoworkbenchReview(val id: String, val repositoryId: String, val createdAt: Long, val contentHash: String, val files: List<CoworkbenchFileReview>, val warnings: List<String>, val summary: String)
data class CoworkbenchSnapshot(val id: String, val repositoryId: String, val reviewId: String, val createdAt: Long, val files: List<CoworkbenchSnapshotFile>)
data class CoworkbenchSnapshotFile(val path: String, val before: String?, val applied: String?)
data class CoworkbenchApprovalRecord(
    val id: String,
    val repositoryId: String,
    val reviewId: String,
    val createdAt: Long,
    val paths: List<String>,
    val status: String,
    val detail: String
)

object CoworkbenchPolicy {
    const val MAX_FILES = 40
    const val MAX_FILE_BYTES = 1_000_000
    const val MAX_TOTAL_BYTES = 4_000_000

    fun safePath(value: String): String {
        require(value.isNotBlank() && value.length <= 1024) { "文件路径无效" }
        require(!value.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(value)) { "只允许仓库内相对路径" }
        val path = value.replace('\\', '/')
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "文件路径包含越界片段" }
        require(path.split('/').none { it == ".git" } && !path.startsWith(".git/")) { "禁止修改 Git 元数据" }
        require(path.none { it.isISOControl() }) { "文件路径包含控制字符" }
        return path
    }

    fun canRecoverInterruptedFile(current: String?, before: String?, applied: String?): Boolean =
        current == before || current == applied

    fun selectStage(files: List<CoworkbenchFileReview>, selectedPaths: Set<String>): List<CoworkbenchFileReview> {
        require(selectedPaths.isNotEmpty()) { "至少选择一个文件阶段应用" }
        require(selectedPaths.all { path -> files.any { it.path == path } }) { "阶段补丁包含审查范围外的文件" }
        return files.filter { it.path in selectedPaths }.also {
            require(it.isNotEmpty()) { "所选阶段没有可应用的文件" }
        }
    }

    fun validate(proposals: List<CoworkbenchProposal>) {
        require(proposals.isNotEmpty() && proposals.size <= MAX_FILES) { "一次补丁必须包含 1 到 40 个文件" }
        val paths = proposals.map { safePath(it.path) }
        require(paths.distinct().size == paths.size) { "补丁包含重复文件路径" }
        var total = 0L
        proposals.forEach { item ->
            val text = item.content ?: return@forEach
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_FILE_BYTES && bytes.none { it == 0.toByte() }) { "补丁文件超限或包含二进制内容" }
            total += bytes.size
            require(total <= MAX_TOTAL_BYTES) { "补丁总大小超过 4 MB" }
        }
    }

    fun impact(path: String, before: String?, after: String?): List<String> {
        val result = mutableListOf<String>()
        val name = path.lowercase()
        if (name.endsWith("androidmanifest.xml") || name.endsWith(".plist")) result += "权限/组件声明变更：逐项核对新增权限、导出组件与外部入口"
        if (name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith("gradle.properties") || name.endsWith("libs.versions.toml")) result += "构建依赖或构建配置变更：检查来源、版本、许可证与传递依赖"
        if (name.startsWith(".github/workflows/") || name.endsWith(".sh") || name.endsWith(".bat")) result += "自动化脚本变更：检查命令执行、凭据暴露和工作流权限"
        val code = after.orEmpty()
        if (Regex("ProcessBuilder|Runtime\\.getRuntime\\(\\)\\.exec|/bin/(sh|bash)|powershell", RegexOption.IGNORE_CASE).containsMatchIn(code)) result += "检测到进程/命令执行入口：高风险，必须人工审查"
        if (Regex("android\\.permission\\.(READ_SMS|SEND_SMS|RECORD_AUDIO|CAMERA|ACCESS_FINE_LOCATION|READ_CONTACTS|CALL_PHONE)", RegexOption.IGNORE_CASE).containsMatchIn(code)) result += "检测到敏感 Android 权限引用：核对最小权限和用户授权"
        if (Regex("http://", RegexOption.IGNORE_CASE).containsMatchIn(code)) result += "检测到明文 HTTP 地址：确认必要性，禁止发送敏感数据"
        if (before == null) result += "新增文件"
        if (after == null) result += "删除文件"
        return result.distinct()
    }

    fun diff(before: String?, after: String?): String {
        if (before == after) return "(无内容变化)"
        val old = before.orEmpty().lines()
        val next = after.orEmpty().lines()
        var prefix = 0
        while (prefix < old.size && prefix < next.size && old[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < next.size - prefix && old[old.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        val out = StringBuilder("@@ -").append(prefix + 1).append(",").append(old.size - prefix - suffix)
            .append(" +").append(prefix + 1).append(",").append(next.size - prefix - suffix).append(" @@\n")
        old.drop(prefix).take(old.size - prefix - suffix).forEach { out.append("-").append(it).append('\n') }
        next.drop(prefix).take(next.size - prefix - suffix).forEach { out.append("+").append(it).append('\n') }
        return if (out.length > 80_000) out.substring(0, 80_000) + "\n… diff 已截断" else out.toString()
    }
}

class AiCoworkbenchService(context: Context, private val git: GitWorkbenchRepository) {
    private val lock = Mutex()
    private val root = File(context.applicationContext.filesDir, "ai-coworkbench").apply { mkdirs() }
    private val reviews = File(root, "reviews").apply { mkdirs() }
    private val snapshots = File(root, "snapshots").apply { mkdirs() }
    private val approvals = File(root, "approvals").apply { mkdirs() }

    suspend fun prepareReview(repositoryId: String, proposals: List<CoworkbenchProposal>): CoworkbenchReview = lock.withLock {
        recoverPendingApprovalsUnlocked()
        val repository = git.localRepositories().firstOrNull { it.id == repositoryId }
            ?: error("本地仓库不存在")
        CoworkbenchPolicy.validate(proposals)
        val staticReport = withContext(Dispatchers.IO) {
            CoworkbenchStaticAnalyzer.analyze(File(repository.localPath))
        }
        val changeImpact = withContext(Dispatchers.IO) {
            CoworkbenchStaticAnalyzer.analyzeProposalsWithBaseline(File(repository.localPath), proposals)
        }
        val files = proposals.map { proposal ->
            val path = CoworkbenchPolicy.safePath(proposal.path)
            val before = readOrNull(repositoryId, path)
            require(before != proposal.content) { "文件 " + path + " 没有实际变化" }
            CoworkbenchFileReview(path, before, proposal.content, CoworkbenchPolicy.diff(before, proposal.content),
                CoworkbenchPolicy.impact(path, before, proposal.content))
        }
        val hash = hash(repositoryId, files)
        val repositoryWarnings = staticReport.findings.map { finding ->
            "${finding.level} · ${finding.category} · ${finding.path}" +
                (finding.line?.let { ":$it" } ?: "") + "：" + finding.message
        } + if (staticReport.truncated) listOf("仓库静态扫描达到安全上限，影响分析结果不完整") else emptyList()
        val changeWarnings = changeImpact.findings.map { finding ->
            "${finding.level} · ${finding.category} · ${finding.path}" +
                (finding.line?.let { ":$it" } ?: "") + "：" + finding.message + "（" + finding.evidence.take(180) + "）"
        } + changeImpact.permissionsAdded.map { "新增权限：$it" } +
            changeImpact.permissionsRemoved.map { "移除权限：$it" } +
            changeImpact.dependenciesAdded.map { "新增依赖声明：$it" } +
            changeImpact.dependenciesRemoved.map { "移除依赖声明：$it" }
        val review = CoworkbenchReview(UUID.randomUUID().toString(), repositoryId, System.currentTimeMillis(), hash,
            files, (files.flatMap { it.impact } + repositoryWarnings + changeWarnings).distinct(),
            "拟变更 " + files.size + " 个文件：新增 " + files.count { it.before == null } +
                "、修改 " + files.count { it.before != null && it.after != null } + "、删除 " + files.count { it.after == null })
        writeJson(File(reviews, review.id + ".json"), encodeReview(review))
        review
    }

    suspend fun loadReview(id: String): CoworkbenchReview = lock.withLock { loadReviewUnlocked(id) }

    suspend fun listContextFiles(repositoryId: String): List<String> = lock.withLock {
        val repository = git.localRepositories().firstOrNull { it.id == repositoryId }
            ?: error("本地仓库不存在")
        withContext(Dispatchers.IO) {
            val root = File(repository.localPath).canonicalFile
            require(root.isDirectory) { "本地仓库目录不可用" }
            root.walkTopDown().take(10_000)
                .onEnter { dir ->
                    dir == root || (dir.name !in setOf(".git", ".gradle", "build", "node_modules", ".idea", ".nestro", "dist", "out") &&
                        !java.nio.file.Files.isSymbolicLink(dir.toPath()))
                }
                .filter { file ->
                    file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                        !isSensitiveContextPath(root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')) &&
                        file.extension.lowercase() in setOf("kt", "kts", "java", "xml", "gradle", "toml", "properties", "json", "yml", "yaml", "md")
                }
                .mapNotNull { file ->
                    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
                    if (!canonical.toPath().startsWith(root.toPath()) || file.length() !in 1..65_536L) null
                    else root.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/')
                }
                .take(120)
                .toList()
                .sorted()
        }
    }

    suspend fun readContextFiles(repositoryId: String, paths: List<String>): String = lock.withLock {
        require(paths.isNotEmpty() && paths.size <= 20) { "上下文文件需选择 1 到 20 个" }
        val normalized = paths.map(CoworkbenchPolicy::safePath)
        require(normalized.distinct().size == normalized.size) { "上下文文件包含重复路径" }
        val repository = git.localRepositories().firstOrNull { it.id == repositoryId }
            ?: error("本地仓库不存在")
        withContext(Dispatchers.IO) {
            val root = File(repository.localPath).canonicalFile
            var total = 0
            buildString {
                normalized.forEach { relative ->
                    val candidate = File(root, relative)
                    var ancestor: File? = candidate
                    while (ancestor != null && ancestor.toPath().startsWith(root.toPath())) {
                        require(!java.nio.file.Files.isSymbolicLink(ancestor.toPath())) {
                            "上下文文件包含符号链接，已拒绝：$relative"
                        }
                        ancestor = ancestor.parentFile
                    }
                    require(!isSensitiveContextPath(relative)) { "疑似凭据或本地配置文件不得作为模型上下文：$relative" }
                    val file = candidate.canonicalFile
                    require(file.toPath().startsWith(root.toPath()) && file.isFile) {
                        "上下文文件无效或路径越界：$relative"
                    }
                    val size = file.length()
                    require(size in 1..24_576L && total + size <= 65_536) { "上下文文件总量超过 64 KB 或单文件超过 24 KB" }
                    val content = file.readText(Charsets.UTF_8)
                    require('\u0000' !in content) { "上下文包含二进制文件：$relative" }
                    total += size.toInt()
                    append("\n--- FILE: ").append(relative).append(" ---\n")
                    append(redactContextSecrets(content)).append("\n")
                }
            }
        }
    }

    private fun isSensitiveContextPath(path: String): Boolean {
        val normalized = path.lowercase().replace('\\', '/')
        val name = normalized.substringAfterLast('/')
        return name == "local.properties" || name == "credentials" || name.startsWith(".env") ||
            listOf("secret", "credential", "keystore", "signing", "private-key", "private_key")
                .any { it in normalized }
    }

    private fun redactContextSecrets(source: String): String {
        var text = source
        val patterns = listOf(
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Regex("(?i)\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|sk-[A-Za-z0-9]{24,})\\b"),
            Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/-]{16,}"),
            Regex("(?i)(\\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|password|secret)\\s*[:=]\\s*[\\\"']?)[A-Za-z0-9_./+=-]{12,}")
        )
        patterns.forEach { pattern ->
            text = pattern.replace(text) { match ->
                val prefix = if (match.groupValues.size > 1) match.groupValues[1] else ""
                prefix + "[REDACTED]"
            }
        }
        return text
    }

    suspend fun analyzeRepository(repositoryId: String): CoworkbenchAnalysisReport = lock.withLock {
        val repository = git.localRepositories().firstOrNull { it.id == repositoryId }
            ?: error("本地仓库不存在")
        withContext(Dispatchers.IO) {
            CoworkbenchStaticAnalyzer.analyze(File(repository.localPath))
        }
    }

    suspend fun listSnapshots(repositoryId: String): List<CoworkbenchSnapshot> = lock.withLock {
        recoverPendingApprovalsUnlocked()
        require(repositoryId.isNotBlank()) { "仓库标识无效" }
        snapshots.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.length() in 1..5_000_000 }
            .mapNotNull { file -> runCatching { decodeSnapshot(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() }
            .filter { it.repositoryId == repositoryId }
            .sortedByDescending { it.createdAt }
            .take(50)
    }

    suspend fun applyApproved(
        reviewId: String,
        expectedHash: String,
        explicitlyApproved: Boolean,
        selectedPaths: Set<String>? = null
    ): CoworkbenchSnapshot = lock.withLock {
        recoverPendingApprovalsUnlocked()
        require(explicitlyApproved) { "未取得用户明确批准，禁止应用补丁" }
        val review = loadReviewUnlocked(reviewId)
        require(review.createdAt + 86_400_000L > System.currentTimeMillis()) { "审查已过期，请重新生成" }
        require(review.contentHash == expectedHash && hash(review.repositoryId, review.files) == expectedHash) { "审查摘要不匹配，请重新审查" }
        require(git.localRepositories().any { it.id == review.repositoryId }) { "目标仓库不存在" }
        val selected = if (selectedPaths == null) review.files
            else CoworkbenchPolicy.selectStage(review.files, selectedPaths)
        selected.forEach { item ->
            require(readOrNull(review.repositoryId, item.path) == item.before) {
                "文件 " + item.path + " 在审查后已变化，已中止以防止覆盖"
            }
        }
        val snapshot = CoworkbenchSnapshot(UUID.randomUUID().toString(), review.repositoryId, review.id,
            System.currentTimeMillis(), selected.map { CoworkbenchSnapshotFile(it.path, it.before, it.after) })
        writeJson(File(snapshots, snapshot.id + ".json"), encodeSnapshot(snapshot))
        val approval = CoworkbenchApprovalRecord(snapshot.id, review.repositoryId, review.id,
            snapshot.createdAt, selected.map { it.path }, "PENDING", "应用事务已持久化，等待文件写入完成")
        writeJson(File(approvals, approval.id + ".json"), encodeApproval(approval))
        try {
            selected.forEach { item ->
                if (item.after == null) git.deleteFile(review.repositoryId, item.path)
                else git.writeFile(review.repositoryId, item.path, item.after)
            }
            writeJson(File(approvals, approval.id + ".json"), encodeApproval(approval.copy(
                status = "APPLIED", detail = "本批文件已全部应用；回滚快照已持久化")))
        } catch (failure: Throwable) {
            val rollbackFailure = withContext(NonCancellable) {
                try {
                    restoreBefore(snapshot)
                    writeJson(File(approvals, approval.id + ".json"), encodeApproval(approval.copy(
                        status = "ROLLED_BACK", detail = "应用失败或被取消，已自动恢复：" + (failure.message ?: "未知错误"))))
                    null
                } catch (rollback: Throwable) {
                    runCatching {
                        writeJson(File(approvals, approval.id + ".json"), encodeApproval(approval.copy(
                            status = "RECOVERY_REQUIRED", detail = "自动恢复失败；快照保留。应用错误：" + failure.message + "；恢复错误：" + rollback.message)))
                    }
                    rollback
                }
            }
            if (rollbackFailure != null) {
                throw IllegalStateException("补丁应用失败且自动恢复失败；快照已保留。应用错误：" + failure.message + "；恢复错误：" + rollbackFailure.message, failure)
            }
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw IllegalStateException("补丁应用失败，已自动恢复：" + failure.message, failure)
        }
        snapshot
    }

    suspend fun rollback(snapshotId: String): CoworkbenchSnapshot = lock.withLock {
        recoverPendingApprovalsUnlocked()
        require(snapshotId.matches(Regex("[0-9a-fA-F-]{36}"))) { "快照 ID 无效" }
        val file = File(snapshots, snapshotId + ".json")
        require(file.isFile && file.length() <= 5_000_000) { "快照不存在或大小无效" }
        val snapshot = decodeSnapshot(JSONObject(file.readText(Charsets.UTF_8)))
        snapshot.files.forEach { item ->
            require(readOrNull(snapshot.repositoryId, item.path) == item.applied) {
                "文件 " + item.path + " 在补丁应用后又发生变化，已中止回滚以保护新内容"
            }
        }
        withContext(NonCancellable) { restoreBefore(snapshot) }
        approvals.listFiles().orEmpty().firstOrNull { it.name == snapshot.id + ".json" }?.let { eventFile ->
            val event = runCatching { decodeApproval(JSONObject(eventFile.readText(Charsets.UTF_8))) }.getOrNull()
            if (event != null) writeJson(eventFile, encodeApproval(event.copy(status = "ROLLED_BACK", detail = "用户通过回滚快照恢复原文件")))
        }
        snapshot
    }

    suspend fun listApprovalHistory(repositoryId: String): List<CoworkbenchApprovalRecord> = lock.withLock {
        recoverPendingApprovalsUnlocked()
        require(repositoryId.isNotBlank()) { "仓库标识无效" }
        approvals.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.length() in 1..256_000 }
            .mapNotNull { file -> runCatching { decodeApproval(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() }
            .filter { it.repositoryId == repositoryId }
            .sortedByDescending { it.createdAt }
            .take(100)
    }

    private suspend fun recoverPendingApprovalsUnlocked() {
        approvals.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.length() in 1..256_000 }
            .forEach { eventFile ->
                val event = runCatching { decodeApproval(JSONObject(eventFile.readText(Charsets.UTF_8))) }.getOrNull() ?: return@forEach
                if (event.status == "PENDING" || event.status == "RECOVERY_REQUIRED") {
                    val snapshotFile = File(snapshots, event.id + ".json")
                    try {
                        require(snapshotFile.isFile && snapshotFile.length() in 1..5_000_000) { "事务快照缺失或无效" }
                        val snapshot = decodeSnapshot(JSONObject(snapshotFile.readText(Charsets.UTF_8)))
                        require(snapshot.id == event.id && snapshot.repositoryId == event.repositoryId) { "事务快照与审批记录不匹配" }
                        withContext(NonCancellable) {
                            snapshot.files.forEach { item ->
                                val current = readOrNull(snapshot.repositoryId, item.path)
                                require(CoworkbenchPolicy.canRecoverInterruptedFile(current, item.before, item.applied)) {
                                    "文件 ${item.path} 在进程中断后被外部修改；为保护新内容，停止自动覆盖"
                                }
                            }
                            restoreBefore(snapshot)
                            writeJson(eventFile, encodeApproval(event.copy(status = "RECOVERED", detail = "检测到未完成事务，已按快照恢复；未覆盖外部修改")))
                        }
                    } catch (failure: Throwable) {
                        writeJson(eventFile, encodeApproval(event.copy(status = "RECOVERY_REQUIRED",
                            detail = "检测到未完成事务，自动恢复失败：" + (failure.message ?: "未知错误"))))
                    }
                }
            }
    }

    private suspend fun readOrNull(repositoryId: String, path: String): String? = try {
        git.readFile(repositoryId, path)
    } catch (error: Exception) {
        if (error.message?.contains("不存在") == true || error.message?.contains("只能读取普通文件") == true) null else throw error
    }

    private suspend fun restoreBefore(snapshot: CoworkbenchSnapshot) {
        snapshot.files.forEach { item ->
            if (item.before == null) {
                if (readOrNull(snapshot.repositoryId, item.path) != null) git.deleteFile(snapshot.repositoryId, item.path)
            } else git.writeFile(snapshot.repositoryId, item.path, item.before)
        }
    }

    private fun loadReviewUnlocked(id: String): CoworkbenchReview {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "审查 ID 无效" }
        val file = File(reviews, id + ".json")
        require(file.isFile && file.length() <= 5_000_000) { "审查记录不存在或大小无效" }
        return decodeReview(JSONObject(file.readText(Charsets.UTF_8)))
    }

    private fun hash(repositoryId: String, files: List<CoworkbenchFileReview>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(repositoryId.toByteArray(Charsets.UTF_8))
        files.sortedBy { it.path }.forEach { item ->
            listOf(item.path, item.before ?: "<absent>", item.after ?: "<deleted>").forEach {
                digest.update(0.toByte()); digest.update(it.toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeJson(file: File, json: JSONObject) {
        val temp = File(file.parentFile, "." + file.name + "." + UUID.randomUUID() + ".tmp")
        try { temp.writeText(json.toString(), Charsets.UTF_8); check(temp.renameTo(file)) { "审查/快照持久化失败" } }
        finally { temp.delete() }
    }

    private fun encodeApproval(value: CoworkbenchApprovalRecord) = JSONObject()
        .put("id", value.id).put("repositoryId", value.repositoryId).put("reviewId", value.reviewId)
        .put("createdAt", value.createdAt).put("paths", JSONArray(value.paths))
        .put("status", value.status).put("detail", value.detail)

    private fun decodeApproval(json: JSONObject): CoworkbenchApprovalRecord {
        val pathsJson = json.getJSONArray("paths")
        val paths = (0 until pathsJson.length()).map { CoworkbenchPolicy.safePath(pathsJson.getString(it)) }
        val result = CoworkbenchApprovalRecord(json.getString("id"), json.getString("repositoryId"),
            json.getString("reviewId"), json.getLong("createdAt"), paths,
            json.getString("status"), json.optString("detail"))
        require(result.id.matches(Regex("[0-9a-fA-F-]{36}")) &&
            result.reviewId.matches(Regex("[0-9a-fA-F-]{36}")) && result.repositoryId.isNotBlank() &&
            paths.isNotEmpty() && paths.size <= CoworkbenchPolicy.MAX_FILES &&
            result.status in setOf("PENDING", "APPLIED", "ROLLED_BACK", "RECOVERED", "RECOVERY_REQUIRED"))
        return result
    }

    private fun encodeReview(value: CoworkbenchReview) = JSONObject()
        .put("id", value.id).put("repositoryId", value.repositoryId).put("createdAt", value.createdAt)
        .put("contentHash", value.contentHash).put("summary", value.summary)
        .put("warnings", JSONArray(value.warnings))
        .put("files", JSONArray().apply { value.files.forEach { item ->
            put(JSONObject().put("path", item.path).put("before", item.before).put("after", item.after)
                .put("diff", item.diff).put("impact", JSONArray(item.impact)))
        } })

    private fun decodeReview(json: JSONObject): CoworkbenchReview {
        val rows = json.getJSONArray("files")
        require(rows.length() in 1..CoworkbenchPolicy.MAX_FILES)
        val files = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            CoworkbenchFileReview(CoworkbenchPolicy.safePath(row.getString("path")),
                if (row.isNull("before")) null else row.getString("before"),
                if (row.isNull("after")) null else row.getString("after"), row.getString("diff"),
                row.getJSONArray("impact").let { array -> (0 until array.length()).map(array::getString) })
        }
        val result = CoworkbenchReview(json.getString("id"), json.getString("repositoryId"), json.getLong("createdAt"),
            json.getString("contentHash"), files,
            json.getJSONArray("warnings").let { array -> (0 until array.length()).map(array::getString) },
            json.getString("summary"))
        require(result.id.matches(Regex("[0-9a-fA-F-]{36}")) && result.repositoryId.isNotBlank())
        require(result.contentHash == hash(result.repositoryId, result.files)) { "审查记录摘要校验失败" }
        return result
    }

    private fun encodeSnapshot(value: CoworkbenchSnapshot) = JSONObject()
        .put("id", value.id).put("repositoryId", value.repositoryId).put("reviewId", value.reviewId)
        .put("createdAt", value.createdAt).put("files", JSONArray().apply { value.files.forEach { item ->
            put(JSONObject().put("path", item.path).put("before", item.before).put("applied", item.applied))
        } })

    private fun decodeSnapshot(json: JSONObject): CoworkbenchSnapshot {
        val rows = json.getJSONArray("files")
        require(rows.length() in 1..CoworkbenchPolicy.MAX_FILES)
        val items = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            CoworkbenchSnapshotFile(CoworkbenchPolicy.safePath(row.getString("path")),
                if (row.isNull("before")) null else row.getString("before"),
                if (row.isNull("applied")) null else row.getString("applied"))
        }
        return CoworkbenchSnapshot(json.getString("id"), json.getString("repositoryId"),
            json.getString("reviewId"), json.getLong("createdAt"), items)
            .also { require(it.id.matches(Regex("[0-9a-fA-F-]{36}")) && it.repositoryId.isNotBlank()) }
    }
}
