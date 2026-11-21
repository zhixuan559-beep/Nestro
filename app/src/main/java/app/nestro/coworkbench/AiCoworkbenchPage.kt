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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nestro.git.GitLocalRepository
import app.nestro.git.GitWorkbenchRepository
import kotlinx.coroutines.launch

@Composable
fun AiCoworkbenchPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val git = remember { GitWorkbenchRepository(context) }
    val service = remember { AiCoworkbenchService(context, git) }
    val scope = rememberCoroutineScope()
    val repositories = remember { git.localRepositories() }
    var selectedRepoId by remember { mutableStateOf(repositories.firstOrNull()?.id.orEmpty()) }
    var repoMenu by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var proposedContent by remember { mutableStateOf("") }
    var deleteFile by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<CoworkbenchReview?>(null) }
    var snapshot by remember { mutableStateOf<CoworkbenchSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AI 共建工作台", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text("先生成补丁审查，再逐文件核对影响；只有点击批准后才会修改本地仓库。当前版本接收待审查的文本变更，不执行补丁中的代码。", style = MaterialTheme.typography.bodySmall)
        if (repositories.isEmpty()) {
            Text("尚无本地 Git 仓库。请先在 Git 工作台克隆一个仓库。")
        } else {
            OutlinedButton(onClick = { repoMenu = true }, modifier = Modifier.fillMaxWidth()) {
                val selected = repositories.firstOrNull { it.id == selectedRepoId }
                Text(selected?.fullName ?: "选择本地仓库")
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
            OutlinedTextField(
                value = path, onValueChange = { path = it; review = null },
                modifier = Modifier.fillMaxWidth(), label = { Text("仓库相对文件路径") },
                singleLine = true, enabled = !busy
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it; review = null }, enabled = !busy)
                Text("将该文件作为删除操作审查")
            }
            if (!deleteFile) {
                OutlinedTextField(
                    value = proposedContent, onValueChange = { proposedContent = it; review = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("待审查的新文件内容") },
                    minLines = 8, enabled = !busy
                )
            }
            Button(
                onClick = {
                    busy = true
                    status = ""
                    scope.launch {
                        try {
                            review = service.prepareReview(selectedRepoId, listOf(CoworkbenchProposal(path, if (deleteFile) null else proposedContent)))
                            snapshot = null
                            status = "审查已生成。请检查完整变更与风险提示，再决定是否批准。"
                        } catch (error: Exception) {
                            status = error.message ?: "生成审查失败"
                        } finally { busy = false }
                    }
                },
                enabled = !busy && selectedRepoId.isNotBlank() && path.isNotBlank() && (deleteFile || proposedContent.isNotEmpty()),
                modifier = Modifier.fillMaxWidth()
            ) { Text("生成补丁审查") }

            review?.let { item ->
                Divider()
                Text(item.summary, style = MaterialTheme.typography.titleMedium)
                Text("审查摘要：${item.contentHash}", style = MaterialTheme.typography.bodySmall)
                if (item.warnings.isNotEmpty()) {
                    Text("影响与风险提示", style = MaterialTheme.typography.titleSmall)
                    item.warnings.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
                } else Text("未触发已配置的风险规则；这不代表代码绝对安全。", style = MaterialTheme.typography.bodySmall)
                item.files.forEach { file ->
                    Text(file.path, style = MaterialTheme.typography.titleSmall)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(file.diff, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                snapshot = service.applyApproved(item.id, item.contentHash, explicitlyApproved = true)
                                status = "补丁已应用；回滚快照已保存。"
                            } catch (error: Exception) {
                                status = error.message ?: "补丁应用失败"
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("我已核对，批准并应用补丁") }
            }
            snapshot?.let { saved ->
                Text("快照已保存：${saved.id}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
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
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("回滚本次变更") }
            }
        }
        if (busy) CircularProgressIndicator()
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}
