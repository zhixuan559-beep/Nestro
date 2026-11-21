package app.nestro.git

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.security.MessageDigest

@Composable
fun GitWorkbenchPage(context: Context) {
    val repo = remember(context) { GitWorkbenchRepository(context) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(repo.accounts.list()) }
    var provider by remember { mutableStateOf(GitProvider.GITHUB) }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var accountMenu by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(emptyList<GitRemoteRepository>()) }
    var locals by remember { mutableStateOf(emptyList<GitLocalRepository>()) }
    var localId by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(emptyList<GitFileEntry>()) }
    var folder by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf("") }
    var originalEditor by remember { mutableStateOf("") }
    var changes by remember { mutableStateOf(emptyList<GitChangeRecord>()) }
    var patch by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf(emptyList<String>()) }
    var currentBranch by remember { mutableStateOf("") }
    var branchMenu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<GitCommitRecord>()) }
    var author by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var newBranch by remember { mutableStateOf("") }
    var newFilePath by remember { mutableStateOf("") }
    var pendingDeleteFile by remember { mutableStateOf("") }
    var pendingDeleteRepo by remember { mutableStateOf("") }
    var pendingDeleteAccount by remember { mutableStateOf<GitAccount?>(null) }
    var revertTarget by remember { mutableStateOf<GitCommitRecord?>(null) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    val selectedLocal = locals.firstOrNull { it.id == localId }

    suspend fun refreshLocals() {
        locals = withContext(Dispatchers.IO) { repo.localRepositories() }
        if (locals.none { it.id == localId }) localId = locals.firstOrNull()?.id.orEmpty()
    }
    suspend fun refreshWorkspace(id: String) {
        changes = runCatching { repo.status(id) }.getOrElse { status = it.message ?: "状态读取失败"; emptyList() }
        branches = runCatching { repo.branches(id) }.getOrElse { status = it.message ?: "分支读取失败"; emptyList() }
        currentBranch = runCatching { repo.currentBranch(id) }.getOrDefault("")
        history = runCatching { repo.history(id, 50) }.getOrElse { status = it.message ?: "提交历史读取失败"; emptyList() }
        patch = runCatching { repo.diffPatch(id) }.getOrElse { "Diff 暂不可用：" + (it.message ?: "未知错误") }
        files = runCatching { repo.files(id, folder) }.getOrElse { status = it.message ?: "文件列表读取失败"; emptyList() }
    }
    LaunchedEffect(Unit) { refreshLocals() }
    LaunchedEffect(localId) {
        if (localId.isNotBlank()) {
            loading = true; folder = ""; file = ""; editor = ""
            runCatching { refreshWorkspace(localId) }.onFailure { status = it.message ?: "仓库读取失败" }
            loading = false
        } else { files = emptyList(); changes = emptyList(); history = emptyList(); branches = emptyList() }
    }
    LaunchedEffect(localId, folder) {
        if (localId.isNotBlank()) runCatching { repo.files(localId, folder) }
            .onSuccess { files = it }.onFailure { status = it.message ?: "目录读取失败" }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Git 工作台", style = MaterialTheme.typography.headlineSmall)
        Text("支持 GitHub、GitLab、Gitee。访问令牌使用 Android Keystore 加密；仓库保存在应用私有目录。", style = MaterialTheme.typography.bodySmall)

        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("账号授权", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { providerMenu = true }) { Text(provider.name) }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        GitProvider.values().forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { provider = item; providerMenu = false }) }
                    }
                    OutlinedTextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(token, { token = it }, label = { Text("Personal Access Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("令牌需要具备仓库读取与写入权限；令牌不会写入仓库 URL，也不会以明文保存在本地。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !loading && username.isNotBlank() && token.isNotBlank(), onClick = {
                    scope.launch {
                        loading = true
                        try {
                            val pending = GitAccount("pending-" + UUID.randomUUID().toString(), provider, username.trim())
                            val verified = repo.verifyAccount(pending, token.trim())
                            val existing = accounts.firstOrNull { it.provider == provider && it.username.equals(verified, ignoreCase = true) }
                            val digest = MessageDigest.getInstance("SHA-256")
                                .digest(verified.lowercase().toByteArray(Charsets.UTF_8))
                                .joinToString("") { byte -> "%02x".format(byte) }.take(32)
                            val id = existing?.id ?: (provider.name.lowercase() + "-" + digest)
                            val account = GitAccount(id, provider, verified, verified)
                            withContext(Dispatchers.IO) { repo.accounts.save(account, token.trim()) }
                            accounts = repo.accounts.list(); accountId = id; username = verified; token = ""
                            status = verified + " 已验证并加密保存。"
                        } catch (e: Exception) { status = "授权失败：" + (e.message ?: "未知错误") }
                        finally { loading = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("验证并保存") }
                accounts.forEach { account ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(account.provider.name + " · " + account.displayName.ifBlank { account.username })
                        TextButton(onClick = { pendingDeleteAccount = account }) { Text("删除") }
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("远程仓库", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { accountMenu = true }, enabled = accounts.isNotEmpty()) { Text(selectedAccount?.username ?: "选择账号") }
                    DropdownMenu(accountMenu, { accountMenu = false }) {
                        accounts.forEach { account -> DropdownMenuItem(text = { Text(account.provider.name + " · " + account.username) }, onClick = { accountId = account.id; remote = emptyList(); accountMenu = false }) }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("搜索仓库") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedButton(enabled = !loading && selectedAccount != null, onClick = {
                    scope.launch {
                        loading = true
                        runCatching { repo.listRemoteRepositories(selectedAccount!!, query.trim()) }
                            .onSuccess { remote = it; status = "找到 " + it.size + " 个仓库。" }
                            .onFailure { status = it.message ?: "仓库列表读取失败" }
                        loading = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("读取仓库") }
                remote.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(item.fullName)
                            Text((if (item.isPrivate) "私有" else "公开") + " · " + item.defaultBranch, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(enabled = !loading && selectedAccount != null, onClick = {
                            scope.launch {
                                loading = true
                                runCatching { repo.clone(selectedAccount!!, item) }
                                    .onSuccess { cloned -> refreshLocals(); localId = cloned.id; status = "已克隆 " + item.fullName }
                                    .onFailure { status = "克隆失败：" + (it.message ?: "未知错误") }
                                loading = false
                            }
                        }) { Text("克隆") }
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本地仓库", style = MaterialTheme.typography.titleMedium)
                locals.forEach { item ->
                    Surface(
                        color = if (localId == item.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { localId = item.id }
                    ) { Column(Modifier.padding(10.dp)) { Text(item.fullName); Text(item.localPath, style = MaterialTheme.typography.labelSmall) } }
                }
                if (selectedLocal != null) {
                    Text("当前分支：" + currentBranch, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { branchMenu = true }) { Text("分支") }
                        DropdownMenu(branchMenu, { branchMenu = false }) {
                            branches.forEach { branch ->
                                DropdownMenuItem(text = { Text(branch) }, onClick = {
                                    branchMenu = false
                                    scope.launch {
                                        loading = true
                                        runCatching { repo.checkout(selectedLocal.id, branch) }
                                            .onSuccess { status = "已切换：" + branch; refreshWorkspace(selectedLocal.id) }
                                            .onFailure { status = it.message ?: "切换失败" }
                                        loading = false
                                    }
                                })
                            }
                        }
                        OutlinedButton(enabled = !loading, onClick = {
                            scope.launch {
                                loading = true
                                refreshWorkspace(selectedLocal.id)
                                loading = false
                            }
                        }) { Text("刷新") }
                        OutlinedButton(enabled = !loading, onClick = {
                            scope.launch {
                                loading = true
                                runCatching { repo.fetch(selectedLocal.id) }
                                    .onSuccess { status = it.message; refreshWorkspace(selectedLocal.id) }
                                    .onFailure { status = it.message ?: "Fetch 失败" }
                                loading = false
                            }
                        }) { Text("Fetch") }
                        OutlinedButton(enabled = !loading, onClick = {
                            scope.launch {
                                loading = true
                                runCatching { repo.pull(selectedLocal.id) }
                                    .onSuccess { status = it.message + if (it.conflicts.isEmpty()) "" else "\n冲突：" + it.conflicts.joinToString() }
                                    .onFailure { status = it.message ?: "Pull 失败" }
                                refreshWorkspace(selectedLocal.id); loading = false
                            }
                        }) { Text("Pull") }
                        OutlinedButton(enabled = !loading, onClick = {
                            scope.launch {
                                loading = true
                                runCatching { repo.push(selectedLocal.id) }.onSuccess { status = it.message }.onFailure { status = it.message ?: "Push 失败" }
                                refreshWorkspace(selectedLocal.id); loading = false
                            }
                        }) { Text("Push") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(newBranch, { newBranch = it }, label = { Text("新分支") }, modifier = Modifier.weight(1f), singleLine = true)
                        Button(enabled = !loading && newBranch.isNotBlank(), onClick = {
                            scope.launch {
                                loading = true
                                runCatching { repo.createBranch(selectedLocal.id, newBranch.trim()) }
                                    .onSuccess { status = "分支已创建"; newBranch = ""; refreshWorkspace(selectedLocal.id) }
                                    .onFailure { status = it.message ?: "创建分支失败" }
                                loading = false
                            }
                        }) { Text("创建") }
                    }
                    Text("变更（" + changes.size + "）", style = MaterialTheme.typography.titleSmall)
                    changes.forEach { change ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(change.changeType + " · " + change.path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (change.changeType == "冲突") TextButton(enabled = !loading, onClick = {
                                scope.launch {
                                    loading = true
                                    runCatching { repo.stageResolvedFile(selectedLocal.id, change.path) }
                                        .onSuccess { status = "已标记冲突文件为已解决。"; refreshWorkspace(selectedLocal.id) }
                                        .onFailure { status = it.message ?: "冲突文件标记失败" }
                                    loading = false
                                }
                            }) { Text("标记已解决") }
                        }
                    }
                    Text("文件", style = MaterialTheme.typography.titleSmall)
                    if (folder.isNotBlank()) TextButton(onClick = { folder = folder.substringBeforeLast('/', "") }) { Text("返回上级：" + folder) }
                    files.forEach { entry ->
                        TextButton(onClick = {
                            if (entry.directory) folder = entry.path
                            else scope.launch {
                                if (file.isNotBlank() && editor != originalEditor) {
                                    status = "当前文件有未保存修改，请先保存后再切换。"
                                    return@launch
                                }
                                loading = true
                                runCatching { repo.readFile(selectedLocal.id, entry.path) }
                                    .onSuccess { file = entry.path; editor = it; originalEditor = it; status = "已打开 " + file }
                                    .onFailure { status = it.message ?: "读取失败" }
                                loading = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text((if (entry.directory) "▸ " else "· ") + entry.path) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(newFilePath, { newFilePath = it }, label = { Text("新建文件相对路径") }, modifier = Modifier.weight(1f), singleLine = true)
                        TextButton(enabled = !loading && newFilePath.isNotBlank(), onClick = {
                            val target = if (folder.isBlank()) newFilePath.trim() else folder + "/" + newFilePath.trim()
                            scope.launch {
                                loading = true
                                if (file.isNotBlank() && editor != originalEditor) {
                                    status = "当前文件有未保存修改，请先保存后再新建文件。"
                                    loading = false
                                    return@launch
                                }
                                runCatching { repo.writeFile(selectedLocal.id, target, "") }
                                    .onSuccess { file = target; editor = ""; originalEditor = ""; newFilePath = ""; status = "空白文件已创建"; refreshWorkspace(selectedLocal.id) }
                                    .onFailure { status = it.message ?: "新建文件失败" }
                                loading = false
                            }
                        }) { Text("新建") }
                    }
                    if (file.isNotBlank()) {
                        Text("编辑：" + file, style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(editor, { editor = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp), minLines = 8, maxLines = 24)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !loading, onClick = {
                                scope.launch {
                                    loading = true
                                    runCatching { repo.writeFile(selectedLocal.id, file, editor) }
                                        .onSuccess { originalEditor = editor; status = "本地文件已保存"; refreshWorkspace(selectedLocal.id) }
                                        .onFailure { status = it.message ?: "保存失败" }
                                    loading = false
                                }
                            }, modifier = Modifier.weight(1f)) { Text("保存文件") }
                            TextButton(enabled = !loading, onClick = { pendingDeleteFile = file }) { Text("删除文件") }
                        }
                    }
                    HorizontalDivider()
                    Text("提交", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(author, { author = it }, label = { Text("提交者姓名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(email, { email = it }, label = { Text("提交邮箱") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(message, { message = it }, label = { Text("提交说明") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Button(enabled = !loading && message.isNotBlank() && author.isNotBlank() && email.isNotBlank(), onClick = {
                        scope.launch {
                            loading = true
                            runCatching { repo.commit(selectedLocal.id, message, author, email) }
                                .onSuccess { status = "提交完成：" + it.take(10); message = ""; refreshWorkspace(selectedLocal.id) }
                                .onFailure { status = it.message ?: "提交失败" }
                            loading = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Commit") }
                    Text("Diff 预览", style = MaterialTheme.typography.titleSmall)
                    Text(patch.ifBlank { "当前没有已跟踪文件差异。" }, style = MaterialTheme.typography.bodySmall)
                    Text("提交历史", style = MaterialTheme.typography.titleSmall)
                    history.forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(item.shortMessage, style = MaterialTheme.typography.bodySmall)
                            Text(item.id.take(10) + " · " + item.author, style = MaterialTheme.typography.labelSmall)
                            if (item.parentCount == 1) {
                                TextButton(onClick = { revertTarget = item }) { Text("反向回滚") }
                            } else {
                                Text("根提交或合并提交暂不支持自动回滚", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    TextButton(enabled = !loading, onClick = { pendingDeleteRepo = selectedLocal.id }) { Text("彻底删除本地副本") }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }

    pendingDeleteAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAccount = null },
            title = { Text("删除代码托管账号？") },
            text = { Text("将从本机删除该账号的加密访问令牌。关联的本地仓库文件会保留，但之后无法对远程仓库执行 Fetch、Pull 或 Push，除非重新授权。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteAccount = null
                    scope.launch {
                        loading = true
                        runCatching { withContext(Dispatchers.IO) { repo.accounts.delete(account.id) } }
                            .onSuccess { accounts = repo.accounts.list(); if (accountId == account.id) accountId = accounts.firstOrNull()?.id.orEmpty(); status = "账号及令牌已删除。" }
                            .onFailure { status = it.message ?: "删除失败" }
                        loading = false
                    }
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteAccount = null }) { Text("取消") } }
        )
    }

    if (pendingDeleteRepo.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteRepo = "" },
            title = { Text("彻底删除本地仓库？") },
            text = { Text("将删除应用私有目录中的完整仓库副本和本地提交记录；远程仓库不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = pendingDeleteRepo
                    pendingDeleteRepo = ""
                    scope.launch {
                        loading = true
                        runCatching { repo.removeLocal(id, true) }
                            .onSuccess { refreshLocals(); status = "本地仓库副本已删除。" }
                            .onFailure { status = it.message ?: "删除失败" }
                        loading = false
                    }
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteRepo = "" }) { Text("取消") } }
        )
    }

    if (pendingDeleteFile.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = "" },
            title = { Text("删除本地文件？") },
            text = { Text("将从本地工作区删除 " + pendingDeleteFile + "。该变更尚未提交，可以通过 Git 恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = selectedLocal
                    val path = pendingDeleteFile
                    pendingDeleteFile = ""
                    if (target != null) scope.launch {
                        loading = true
                        runCatching { repo.deleteFile(target.id, path) }
                            .onSuccess { file = ""; editor = ""; originalEditor = ""; status = "文件已删除"; refreshWorkspace(target.id) }
                            .onFailure { status = it.message ?: "删除失败" }
                        loading = false
                    }
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteFile = "" }) { Text("取消") } }
        )
    }

    revertTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revertTarget = null },
            title = { Text("反向回滚提交？") },
            text = { Text("将创建反向提交，不删除后续历史；工作区必须干净。") },
            confirmButton = {
                TextButton(onClick = {
                    val current = selectedLocal
                    revertTarget = null
                    if (current != null) scope.launch {
                        loading = true
                        runCatching { repo.revert(current.id, target.id, author, email) }
                            .onSuccess { status = it.message + if (it.conflicts.isEmpty()) "" else "\n冲突：" + it.conflicts.joinToString(); refreshWorkspace(current.id) }
                            .onFailure { status = it.message ?: "回滚失败" }
                        loading = false
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { revertTarget = null }) { Text("取消") } }
        )
    }
}
