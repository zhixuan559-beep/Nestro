package app.nestro.coworkbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nestro.git.GitWorkbenchRepository
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AiCoworkbenchPage(onBack: () -> Unit, onUseChatPrompt: (String) -> Unit) {
    val context = LocalContext.current
    val git = remember { GitWorkbenchRepository(context) }
    val service = remember { AiCoworkbenchService(context, git) }
    val validationRunner = remember { GitHubActionsCoworkbenchRunner(context) }
    val scope = rememberCoroutineScope()
    val repositories = remember { git.localRepositories() }
    var selectedRepoId by remember { mutableStateOf(repositories.firstOrNull()?.id.orEmpty()) }
    var repoMenu by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf("") }
    var workflowFile by remember { mutableStateOf("android.yml") }
    var patchJson by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<CoworkbenchReview?>(null) }
    var snapshot by remember { mutableStateOf<CoworkbenchSnapshot?>(null) }
    var snapshotHistory by remember { mutableStateOf<List<CoworkbenchSnapshot>>(emptyList()) }
    LaunchedEffect(selectedRepoId) { if (selectedRepoId.isNotBlank()) snapshotHistory = runCatching { service.listSnapshots(selectedRepoId) }.getOrDefault(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AI 共建工作台", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text("先让当前模型按结构化格式提出代码变更，再逐文件审查。模型输出不会自动写入仓库，也不会在手机上执行生成代码。",
            style = MaterialTheme.typography.bodySmall)
        if (repositories.isEmpty()) {
            Text("尚无本地 Git 仓库。请先在 Git 工作台克隆一个仓库。")
        } else {
            OutlinedButton(onClick = { repoMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(repositories.firstOrNull { it.id == selectedRepoId }?.fullName ?: "选择本地仓库")
            }
            DropdownMenu(expanded = repoMenu, onDismissRequest = { repoMenu = false }) {
                repositories.forEach { repo ->
                    DropdownMenuItem(text = { Text(repo.fullName) }, onClick = {
                        selectedRepoId = repo.id
                        review = null
                        snapshot = null
                        repoMenu = false
                    })
                }
            }
            OutlinedTextField(value = task, onValueChange = { task = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("本次开发目标") }, minLines = 3)
            Button(onClick = {
                val prompt = """
你正在为 Nestro Android 项目提出代码补丁。目标：${task.trim()}
请只输出一个 JSON 对象，不要 Markdown 代码围栏，格式必须是：
{"files":[{"path":"仓库相对路径","content":"该文件完整的新内容或 null（表示删除）"}]}
要求：
- 只修改完成目标所必需的文件；每个文件给出完整内容，不要输出 diff 片段。
- 不得修改 .git、仓库外路径、密钥、签名文件或 CI 凭据。
- 不得加入自动执行任意 Shell/脚本的逻辑，不得静默新增权限。
- 如果涉及依赖、权限、构建脚本或安全边界，在 JSON 外不要添加说明；风险将在审查阶段检查。
- 不确定现有代码结构时，先要求补充相关源码，不得臆造 API。
""".trimIndent()
                onUseChatPrompt(prompt)
            }, enabled = task.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("交给当前模型生成补丁方案")
            }
            OutlinedTextField(value = patchJson, onValueChange = { patchJson = it; review = null },
                modifier = Modifier.fillMaxWidth(), label = { Text("粘贴模型返回的补丁 JSON") }, minLines = 8, enabled = !busy)
            Button(onClick = {
                busy = true
                status = ""
                scope.launch {
                    try {
                        val raw = patchJson.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                        val rows = JSONObject(raw).getJSONArray("files")
                        val proposals = (0 until rows.length()).map { index ->
                            val row = rows.getJSONObject(index)
                            require(row.has("path") && row.has("content")) { "每个文件都必须包含 path 和 content" }
                            CoworkbenchProposal(row.getString("path"), if (row.isNull("content")) null else row.getString("content"))
                        }
                        review = service.prepareReview(selectedRepoId, proposals)
                        snapshot = null
                        status = "审查已生成。逐文件核对 diff 和风险提示后，再决定是否批准。"
                    } catch (error: Exception) {
                        status = error.message ?: "生成审查失败"
                    } finally { busy = false }
                }
            }, enabled = !busy && selectedRepoId.isNotBlank() && patchJson.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("解析多文件补丁并生成审查")
            }

            review?.let { item ->
                Divider()
                Text(item.summary, style = MaterialTheme.typography.titleMedium)
                Text("SHA-256 审查摘要：${item.contentHash}", style = MaterialTheme.typography.bodySmall)
                Text("影响与风险提示", style = MaterialTheme.typography.titleSmall)
                if (item.warnings.isEmpty()) Text("未触发已配置规则；这不代表代码绝对安全。", style = MaterialTheme.typography.bodySmall)
                item.warnings.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
                item.files.forEach { file ->
                    Text(file.path, style = MaterialTheme.typography.titleSmall)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(file.diff, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            snapshot = service.applyApproved(item.id, item.contentHash, explicitlyApproved = true)
                            snapshotHistory = service.listSnapshots(selectedRepoId)
                            status = "补丁已应用；回滚快照已持久化。"
                        } catch (error: Exception) {
                            status = error.message ?: "补丁应用失败"
                        } finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("我已核对，批准并应用全部变更")
                }
            }
            snapshot?.let { saved ->
                Text("快照已保存：${saved.id}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            service.rollback(saved.id)
                            status = "已按快照回滚。"
                            snapshot = null
                            review = null
                        } catch (error: Exception) {
                            status = error.message ?: "回滚失败"
                        } finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("回滚本次变更") }
            }
        }
        if (snapshotHistory.isNotEmpty()) {
            Divider()
            Text("历史回滚快照", style = MaterialTheme.typography.titleMedium)
            snapshotHistory.forEach { saved ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("快照 " + saved.id.take(8) + " · " + saved.files.size + " 个文件", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                service.rollback(saved.id)
                                snapshotHistory = service.listSnapshots(selectedRepoId)
                                snapshot = null
                                review = null
                                status = "已按历史快照回滚。"
                            } catch (error: Exception) {
                                status = error.message ?: "回滚失败"
                            } finally { busy = false }
                        }
                    }) { Text("回滚") }
                }
            }
        }
        Divider()
        Text("隔离构建与测试", style = MaterialTheme.typography.titleMedium)
        Text("使用 GitHub-hosted 临时 Runner 执行仓库 Workflow；不会在 Android 进程中运行模型生成的 Shell 或构建脚本。请先将已审查变更提交并推送到 GitHub 分支。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = workflowFile, onValueChange = { workflowFile = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Workflow 文件名") }, singleLine = true, enabled = !busy)
        Button(onClick = {
            val selected = repositories.firstOrNull { it.id == selectedRepoId }
            if (selected == null) {
                status = "请先选择本地 GitHub 仓库。"
            } else {
                busy = true
                scope.launch {
                    try {
                        val branch = git.currentBranch(selected.id)
                        val result = validationRunner.dispatchAndWait(selected, workflowFile, branch)
                        status = if (result.successful) "隔离构建与测试通过：" + result.url
                            else "隔离构建结束，结论：" + (result.conclusion ?: result.status) + "；" + result.url
                    } catch (error: Exception) {
                        status = error.message ?: "隔离构建未能启动"
                    } finally { busy = false }
                }
            }
        }, enabled = !busy && CoworkbenchReleaseGate.ENABLE_REMOTE_VALIDATION && workflowFile.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("启动隔离构建/测试") }
        if (!CoworkbenchReleaseGate.ENABLE_REMOTE_VALIDATION) {
            Text("当前按既定流程锁定：十九个模块代码全部完成前，不开放统一编译、自动化、模拟器或真机验收。", style = MaterialTheme.typography.bodySmall)
        }
        if (busy) CircularProgressIndicator()
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}
