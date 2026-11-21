package app.nestro.git

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.api.CreateBranchCommand
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class GitProvider(val apiBase: String) {
    GITHUB("https://api.github.com"),
    GITLAB("https://gitlab.com/api/v4"),
    GITEE("https://gitee.com/api/v5")
}

data class GitAccount(
    val id: String,
    val provider: GitProvider,
    val username: String,
    val displayName: String = ""
)

data class GitRemoteRepository(
    val id: String,
    val provider: GitProvider,
    val fullName: String,
    val description: String,
    val cloneUrl: String,
    val defaultBranch: String,
    val isPrivate: Boolean
)

data class GitLocalRepository(
    val id: String,
    val accountId: String,
    val provider: GitProvider,
    val fullName: String,
    val cloneUrl: String,
    val localPath: String,
    val defaultBranch: String
)

data class GitFileEntry(val path: String, val directory: Boolean, val size: Long)
data class GitCommitRecord(val id: String, val shortMessage: String, val author: String, val timestamp: Long)
data class GitChangeRecord(val path: String, val changeType: String)
data class GitOperationResult(val success: Boolean, val message: String, val conflicts: List<String> = emptyList())

object GitWorkbenchPolicy {
    fun validAccountName(value: String): Boolean =
        value.isNotBlank() && value.length <= 120 && value.none { it.isISOControl() }

    fun validBranchName(value: String): Boolean =
        value.isNotBlank() && value.length <= 120 &&
            !value.startsWith("-") && !value.startsWith("/") && !value.endsWith("/") &&
            !value.contains("..") && !value.contains("@{") &&
            value.none { it.isWhitespace() || it in charArrayOf('~', '^', ':', '?', '*', '[', '\\', ' ') }

    fun validCloneUrl(provider: GitProvider, value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.host.isNullOrBlank()) return false
        val expected = when (provider) {
            GitProvider.GITHUB -> setOf("github.com")
            GitProvider.GITLAB -> setOf("gitlab.com")
            GitProvider.GITEE -> setOf("gitee.com")
        }
        return uri.host.lowercase() in expected && uri.port in setOf(-1, 443) &&
            uri.rawQuery == null && uri.rawFragment == null
    }

    fun safeRelativePath(value: String): String {
        require(value.isNotBlank() && value.length <= 1024) { "文件路径不能为空且不能超过 1024 个字符" }
        val normalized = value.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "文件路径无效"
        }
        require(!normalized.startsWith(".git/") && normalized != ".git") { "不能直接修改 Git 内部元数据" }
        return normalized
    }
}

class GitAccountStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<GitAccount> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        return decode(raw) ?: run {
            if (!prefs.contains(CORRUPT_KEY)) {
                check(prefs.edit().putString(CORRUPT_KEY, raw)
                    .putLong(CORRUPT_AT, System.currentTimeMillis()).commit()) { "无法隔离损坏的 Git 账号配置" }
            }
            emptyList()
        }
    }

    private fun decode(raw: String): List<GitAccount>? = runCatching {
        val array = JSONArray(raw)
        require(array.length() <= 20)
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val account = GitAccount(
                row.getString("id"),
                GitProvider.valueOf(row.getString("provider")),
                row.getString("username"),
                row.optString("displayName")
            )
            require(account.id.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            require(GitWorkbenchPolicy.validAccountName(account.username))
            account
        }.also { require(it.map(GitAccount::id).distinct().size == it.size) }
    }.getOrNull()

    @Synchronized
    fun save(account: GitAccount, token: String) {
        require(account.id.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        require(GitWorkbenchPolicy.validAccountName(account.username))
        require(token.isNotBlank() && token.length <= 4096) { "访问令牌不能为空且不能超过 4096 个字符" }
        val existing = list()
        require(existing.any { it.id == account.id } || existing.size < 20) { "最多保存 20 个代码托管账号" }
        val previousToken = existing.firstOrNull { it.id == account.id }?.let { GitCredentialVault.read(app, account.id) }.orEmpty()
        GitCredentialVault.write(app, account.id, token)
        val next = existing.filterNot { it.id == account.id } + account.copy(
            username = account.username.trim(),
            displayName = account.displayName.take(120)
        )
        try {
            check(prefs.edit().putString(KEY, encode(next)).commit()) { "账号配置保存失败" }
        } catch (error: Exception) {
            if (previousToken.isNotBlank()) GitCredentialVault.write(app, account.id, previousToken)
            else GitCredentialVault.delete(app, account.id)
            throw error
        }
    }

    @Synchronized
    fun token(accountId: String): String = GitCredentialVault.read(app, accountId)

    @Synchronized
    fun delete(accountId: String) {
        val current = list()
        val previousToken = GitCredentialVault.read(app, accountId)
        GitCredentialVault.delete(app, accountId)
        try {
            check(prefs.edit().putString(KEY, encode(current.filterNot { it.id == accountId })).commit()) { "账号配置删除失败" }
        } catch (error: Exception) {
            if (previousToken.isNotBlank()) GitCredentialVault.write(app, accountId, previousToken)
            throw error
        }
    }

    private fun encode(items: List<GitAccount>) = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("id", it.id).put("provider", it.provider.name)
                .put("username", it.username).put("displayName", it.displayName))
        }
    }.toString()

    companion object {
        private const val KEY = "git_accounts_v1"
        private const val CORRUPT_KEY = "git_accounts_v1_unreadable_backup"
        private const val CORRUPT_AT = "git_accounts_v1_unreadable_backup_at"
    }
}

private object GitCredentialVault {
    private const val ALIAS = "nestro_git_credentials_v1"
    private const val PREFIX = "git_access_token_enc_v1_"

    fun write(context: Context, id: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().putString(PREFIX + id, packed).commit()) { "Git 访问令牌加密保存失败" }
    }

    fun read(context: Context, id: String): String {
        val packed = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .getString(PREFIX + id, null) ?: return ""
        return runCatching {
            val parts = packed.split(":")
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse { throw IllegalStateException("Git 访问令牌无法解密，请重新授权", it) }
    }

    fun delete(context: Context, id: String) {
        check(context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().remove(PREFIX + id).commit()) { "Git 访问令牌删除失败" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}

class GitProviderApi(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build()) {

    suspend fun verify(account: GitAccount, token: String): String = withContext(Dispatchers.IO) {
        val path = when (account.provider) {
            GitProvider.GITHUB -> "/user"
            GitProvider.GITLAB -> "/user"
            GitProvider.GITEE -> "/user"
        }
        val json = requestJson(account, token, path)
        val username = when (account.provider) {
            GitProvider.GITHUB -> json.optString("login")
            GitProvider.GITLAB -> json.optString("username")
            GitProvider.GITEE -> json.optString("login")
        }
        require(username.isNotBlank()) { "服务商未返回有效账号信息" }
        username
    }

    suspend fun repositories(account: GitAccount, token: String, query: String = ""): List<GitRemoteRepository> =
        withContext(Dispatchers.IO) {
            val path = when (account.provider) {
                GitProvider.GITHUB -> if (query.isBlank()) "/user/repos?per_page=100&sort=updated" else
                    "/search/repositories?q=" + enc(query) + "&per_page=100"
                GitProvider.GITLAB -> "/projects?membership=true&per_page=100&order_by=last_activity_at" +
                    if (query.isBlank()) "" else "&search=" + enc(query)
                GitProvider.GITEE -> if (query.isBlank()) "/user/repos?per_page=100&sort=updated" else
                    "/search/repositories?q=" + enc(query) + "&per_page=100"
            }
            val raw = request(account, token, path)
            val array = if (account.provider == GitProvider.GITHUB && query.isNotBlank()) {
                JSONObject(raw).optJSONArray("items") ?: JSONArray()
            } else JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val fullName = when (account.provider) {
                    GitProvider.GITHUB -> row.optString("full_name")
                    GitProvider.GITLAB -> row.optString("path_with_namespace")
                    GitProvider.GITEE -> row.optString("full_name")
                }
                val cloneUrl = when (account.provider) {
                    GitProvider.GITHUB -> row.optString("clone_url")
                    GitProvider.GITLAB -> row.optString("http_url_to_repo")
                    GitProvider.GITEE -> row.optString("clone_url")
                }
                if (fullName.isBlank() || cloneUrl.isBlank()) return@mapNotNull null
                GitRemoteRepository(
                    id = row.opt("id")?.toString() ?: fullName,
                    provider = account.provider,
                    fullName = fullName,
                    description = row.optString("description").take(500),
                    cloneUrl = cloneUrl,
                    defaultBranch = row.optString("default_branch").ifBlank { "main" },
                    isPrivate = when (account.provider) {
                        GitProvider.GITHUB -> row.optBoolean("private")
                        GitProvider.GITLAB -> row.optString("visibility") != "public"
                        GitProvider.GITEE -> row.optBoolean("private")
                    }
                )
            }
        }

    private fun requestJson(account: GitAccount, token: String, path: String): JSONObject =
        JSONObject(request(account, token, path))

    private fun request(account: GitAccount, token: String, path: String): String {
        require(path.startsWith("/") && !path.startsWith("//"))
        val builder = okhttp3.Request.Builder().url(account.provider.apiBase + path)
            .get().header("Accept", "application/json")
            .header("User-Agent", "Nestro-Android")
        when (account.provider) {
            GitProvider.GITHUB -> builder.header("Authorization", "Bearer $token")
                .header("X-GitHub-Api-Version", "2022-11-28")
            GitProvider.GITLAB -> builder.header("PRIVATE-TOKEN", token)
            GitProvider.GITEE -> builder.header("Authorization", "token $token")
        }
        return client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                "代码托管服务 HTTP ${response.code}：" + message.ifBlank { body.take(500) }
            }
            require(body.length <= 4_000_000) { "代码托管服务响应超过 4 MB" }
            body
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

class GitWorkbenchRepository(context: Context, private val api: GitProviderApi = GitProviderApi()) {
    private val app = context.applicationContext
    val accounts = GitAccountStore(app)
    private val root = File(app.filesDir, "git-workspaces").apply { mkdirs() }

    suspend fun verifyAccount(account: GitAccount, token: String) = api.verify(account, token)
    suspend fun listRemoteRepositories(account: GitAccount, query: String = ""): List<GitRemoteRepository> =
        api.repositories(account, accounts.token(account.id), query)

    suspend fun clone(account: GitAccount, remote: GitRemoteRepository): GitLocalRepository = withContext(Dispatchers.IO) {
        require(remote.provider == account.provider)
        require(GitWorkbenchPolicy.validCloneUrl(remote.provider, remote.cloneUrl)) { "远程仓库地址不属于已选代码托管服务商，已阻止克隆" }
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        try {
            val currentBranch = Git.cloneRepository().setURI(remote.cloneUrl).setDirectory(directory)
                .setCredentialsProvider(credentials(account)).setCloneAllBranches(true).call().use { git ->
                    git.repository.branch
                }
            val local = GitLocalRepository(id, account.id, account.provider, remote.fullName,
                remote.cloneUrl, directory.absolutePath, currentBranch.ifBlank { remote.defaultBranch })
            saveLocal(local)
            local
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun localRepositories(): List<GitLocalRepository> {
        val prefs = app.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(LOCAL_KEY, "[]").orEmpty()) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val local = runCatching { decodeLocal(row) }.getOrNull() ?: return@mapNotNull null
            val path = runCatching { File(local.localPath).canonicalFile.toPath() }.getOrNull() ?: return@mapNotNull null
            if (path.startsWith(root.canonicalFile.toPath()) && File(path.toFile(), ".git").isDirectory) local else null
        }
    }

    suspend fun status(id: String): List<GitChangeRecord> = withRepo(id) { git ->
        val status = git.status().call()
        (status.added.map { GitChangeRecord(it, "新增") } +
            status.changed.map { GitChangeRecord(it, "修改") } +
            status.modified.map { GitChangeRecord(it, "修改") } +
            status.removed.map { GitChangeRecord(it, "删除") } +
            status.missing.map { GitChangeRecord(it, "缺失") } +
            status.untracked.map { GitChangeRecord(it, "未跟踪") } +
            status.conflicting.map { GitChangeRecord(it, "冲突") }).distinctBy { it.path }.sortedBy { it.path }
    }

    suspend fun files(id: String, relativePath: String = ""): List<GitFileEntry> = withContext(Dispatchers.IO) {
        val local = requireLocal(id)
        val base = File(local.localPath).canonicalFile.toPath()
        val target = if (relativePath.isBlank()) base else resolveSafe(base, relativePath)
        require(target.toFile().isDirectory) { "目标不是目录" }
        Files.list(target).use { stream ->
            stream.filter { it.fileName.toString() != ".git" && !Files.isSymbolicLink(it) }
                .limit(500)
                .map { path ->
                    val file = path.toFile()
                    GitFileEntry(base.relativize(path).toString().replace(File.separatorChar, '/'),
                        file.isDirectory, if (file.isFile) file.length() else 0L)
                }.collect(Collectors.toList()).sortedBy { it.path }
        }
    }

    suspend fun readFile(id: String, relativePath: String): String = withContext(Dispatchers.IO) {
        val local = requireLocal(id)
        val rootPath = File(local.localPath).canonicalFile.toPath()
        val path = resolveSafe(rootPath, relativePath)
        require(path.toFile().canonicalFile.toPath().startsWith(rootPath)) { "文件路径越界" }
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "只能读取普通文件" }
        require(Files.size(path) <= MAX_FILE_BYTES) { "文件超过 2 MB，暂不支持手机端编辑" }
        val bytes = Files.readAllBytes(path)
        require(bytes.none { it == 0.toByte() }) { "检测到二进制文件，手机编辑器仅支持 UTF-8 文本" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { throw IllegalArgumentException("文件不是有效的 UTF-8 文本，已阻止编辑", it) }
    }

    suspend fun writeFile(id: String, relativePath: String, content: String) = withContext(Dispatchers.IO) {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "文件内容超过 2 MB" }
        val local = requireLocal(id)
        val rootPath = File(local.localPath).canonicalFile.toPath()
        val path = resolveSafe(rootPath, relativePath)
        Files.createDirectories(path.parent)
        var parent: Path? = path.parent
        while (parent != null && parent.startsWith(rootPath)) {
            require(!Files.isSymbolicLink(parent)) { "不允许通过符号链接写入仓库外部路径" }
            parent = parent.parent
        }
        val temp = Files.createTempFile(path.parent, ".nestro-edit-", ".tmp")
        try {
            Files.write(temp, content.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    suspend fun createBranch(id: String, name: String, checkout: Boolean = true) = withRepo(id) { git ->
        require(GitWorkbenchPolicy.validBranchName(name)) { "分支名称无效" }
        require(git.repository.findRef("refs/heads/$name") == null) { "本地分支已存在" }
        if (checkout) require(git.status().call().isClean) { "工作区有未提交修改，请先提交或暂存后再切换分支" }
        val defaultBranch = requireLocal(id).defaultBranch
        git.branchCreate().setName(name).setStartPoint("origin/$defaultBranch")
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM).call()
        if (checkout) git.checkout().setName(name).call()
    }

    suspend fun checkout(id: String, name: String) = withRepo(id) { git ->
        require(GitWorkbenchPolicy.validBranchName(name)) { "分支名称无效" }
        require(git.status().call().isClean) { "工作区有未提交修改，请先提交或暂存后再切换分支" }
        val remoteName = name.removePrefix("origin/")
        val remoteRef = "refs/remotes/origin/$remoteName"
        if (git.repository.findRef("refs/heads/$name") == null && git.repository.findRef(remoteRef) != null) {
            git.checkout().setCreateBranch(true).setName(remoteName)
                .setStartPoint("origin/$remoteName")
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM).call()
        } else {
            git.checkout().setName(name).call()
        }
    }

    suspend fun branches(id: String): List<String> = withRepo(id) { git ->
        val refs = git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL).call().map { it.name }
        val local = refs.filter { it.startsWith("refs/heads/") }.map { it.removePrefix("refs/heads/") }.toSet()
        val remoteOnly = refs.filter { it.startsWith("refs/remotes/origin/") }
            .map { it.removePrefix("refs/remotes/origin/") }
            .filterNot { it == "HEAD" || it in local }
            .map { "origin/" + it }
        (local + remoteOnly).distinct().sorted()
    }

    suspend fun commit(id: String, message: String, authorName: String, authorEmail: String): String = withRepo(id) { git ->
        require(message.isNotBlank() && message.length <= 500) { "提交说明不能为空且不能超过 500 个字符" }
        require(authorName.isNotBlank() && authorName.length <= 120)
        require(authorEmail.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "提交邮箱格式无效" }
        git.add().addFilepattern(".").call()
        require(!git.status().call().isClean) { "没有可提交的变更" }
        git.commit().setMessage(message.trim()).setAuthor(PersonIdent(authorName.trim(), authorEmail.trim())).call().name
    }

    suspend fun pull(id: String): GitOperationResult = withRepo(id) { git ->
        require(git.status().call().isClean) { "工作区存在未提交变更，请先提交或暂存后再拉取" }
        val result = git.pull().setCredentialsProvider(credentials(requireAccount(requireLocal(id).accountId)))
            .setRebase(false).call()
        val merge = result.mergeResult
        val conflicts = merge?.conflicts?.keys?.toList().orEmpty()
        when {
            conflicts.isNotEmpty() -> GitOperationResult(false, "拉取后出现合并冲突，请逐文件处理。", conflicts)
            result.isSuccessful -> GitOperationResult(true, "拉取完成。")
            else -> GitOperationResult(false, "拉取未完成：" + (merge?.mergeStatus?.name ?: "远程同步失败"))
        }
    }

    suspend fun push(id: String): GitOperationResult = withRepo(id) { git ->
        val account = requireAccount(requireLocal(id).accountId)
        val branch = git.repository.branch
        val results = git.push().setCredentialsProvider(credentials(account))
            .setRefSpecs(RefSpec("HEAD:refs/heads/$branch")).call()
        val messages = results.flatMap { it.remoteUpdates }.map { it.status.name }
        val failed = messages.any { it !in setOf("OK", "UP_TO_DATE") }
        GitOperationResult(!failed, if (failed) "推送未完成：" + messages.distinct().joinToString("、") else "推送完成。")
    }

    suspend fun history(id: String, limit: Int = 100): List<GitCommitRecord> = withRepo(id) { git ->
        require(limit in 1..200)
        git.log().setMaxCount(limit).call().map { commit ->
            GitCommitRecord(commit.name, commit.shortMessage.take(300),
                commit.authorIdent?.name.orEmpty(), commit.commitTime.toLong() * 1000L)
        }
    }

    suspend fun diff(id: String): List<GitChangeRecord> = withRepo(id) { git ->
        val entries = (git.diff().call() + git.diff().setCached(true).call())
        entries.map { GitChangeRecord(it.newPath.takeIf { path -> path != "/dev/null" } ?: it.oldPath, it.changeType.name) }
            .distinctBy { it.path }.sortedBy { it.path }
    }

    suspend fun diffPatch(id: String): String = withRepo(id) { git ->
        val output = ByteArrayOutputStream()
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.format(git.diff().call())
            formatter.format(git.diff().setCached(true).call())
        }
        String(output.toByteArray(), Charsets.UTF_8).take(200_000)
    }

    suspend fun revert(id: String, commitId: String): GitOperationResult = withRepo(id) { git ->
        require(commitId.matches(Regex("[0-9a-fA-F]{7,40}"))) { "提交 ID 格式无效" }
        require(git.status().call().isClean) { "工作区存在未提交变更，请先处理后再回滚" }
        val commit = git.repository.resolve(commitId + "^{commit}") ?: error("找不到指定提交")
        val result = git.revert().include(commit).call()
        if (result.status == org.eclipse.jgit.api.RevertResult.Status.CONFLICTING) {
            GitOperationResult(false, "回滚产生冲突，请人工处理。", result.failingPaths?.keys?.toList().orEmpty())
        } else {
            GitOperationResult(true, "已生成反向提交：" + (result.newHead?.name ?: "完成"))
        }
    }

    suspend fun removeLocal(id: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val local = requireLocal(id)
        val next = localRepositories().filterNot { it.id == id }
        saveLocals(next)
        if (deleteFiles) File(local.localPath).deleteRecursively()
    }

    private suspend fun <T> withRepo(id: String, block: (Git) -> T): T = withContext(Dispatchers.IO) {
        val local = requireLocal(id)
        Git.open(File(local.localPath)).use(block)
    }

    private fun credentials(account: GitAccount) =
        UsernamePasswordCredentialsProvider(account.username, accounts.token(account.id))

    private fun requireAccount(id: String) = accounts.list().firstOrNull { it.id == id }
        ?: error("代码托管账号不存在，请重新添加")

    private fun requireLocal(id: String) = runCatching {
        val row = localRepositories().firstOrNull { it.id == id } ?: error("本地仓库不存在")
        require(File(row.localPath).canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        row
    }.getOrElse { throw IllegalStateException(it.message ?: "本地仓库无效", it) }

    private fun resolveSafe(rootPath: Path, relative: String): Path {
        val safe = GitWorkbenchPolicy.safeRelativePath(relative)
        val path = rootPath.resolve(safe).normalize()
        require(path.startsWith(rootPath)) { "文件路径越界" }
        val canonical = path.toFile().canonicalFile.toPath()
        require(canonical.startsWith(rootPath)) { "文件路径不能通过符号链接越界" }
        return path
    }

    private suspend fun saveLocal(local: GitLocalRepository) {
        saveLocals(localRepositories() + local)
    }

    private fun saveLocals(items: List<GitLocalRepository>) {
        val json = JSONArray().apply {
            items.forEach {
                put(JSONObject().put("id", it.id).put("accountId", it.accountId)
                    .put("provider", it.provider.name).put("fullName", it.fullName)
                    .put("cloneUrl", it.cloneUrl).put("localPath", it.localPath)
                    .put("defaultBranch", it.defaultBranch))
            }
        }.toString()
        check(app.getSharedPreferences("nestro_local", Context.MODE_PRIVATE).edit()
            .putString(LOCAL_KEY, json).commit()) { "本地仓库索引保存失败" }
    }

    private fun decodeLocal(row: JSONObject) = GitLocalRepository(
        row.getString("id"), row.getString("accountId"), GitProvider.valueOf(row.getString("provider")),
        row.getString("fullName"), row.getString("cloneUrl"), row.getString("localPath"),
        row.optString("defaultBranch", "main")
    )

    companion object {
        private const val LOCAL_KEY = "git_local_repositories_v1"
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }
}
