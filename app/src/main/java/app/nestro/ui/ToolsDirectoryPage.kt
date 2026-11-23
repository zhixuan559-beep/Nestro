package app.nestro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class ToolDirectoryEntry(val title: String, val description: String, val destination: String)

@Composable
internal fun ToolsDirectoryPage(onNavigate: (String) -> Unit) {
    val groups = listOf(
        "模型与智能服务" to listOf(
            ToolDirectoryEntry("模型管理", "API、模型供应商、默认模型与故障切换", "models"),
            ToolDirectoryEntry("双模型比较", "并排比较不同模型的回答", "comparison"),
            ToolDirectoryEntry("语音", "语音输入与语音服务配置", "voice")
        ),
        "技能与自动化" to listOf(
            ToolDirectoryEntry("Skills", "管理可复用技能与技能包", "skills"),
            ToolDirectoryEntry("工作流", "创建和管理可复用工作流", "workflows"),
            ToolDirectoryEntry("插件", "管理扩展插件及其权限", "plugins"),
            ToolDirectoryEntry("MCP", "连接外部工具服务", "mcp"),
            ToolDirectoryEntry("能力与授权", "查看工具能力、项目授权和调用审计", "capabilities")
        ),
        "资料与创作" to listOf(
            ToolDirectoryEntry("知识库", "导入资料并在对话中检索", "knowledge"),
            ToolDirectoryEntry("产物库", "查看对话生成的文件与成果", "artifacts"),
            ToolDirectoryEntry("媒体工作区", "按需进入媒体编辑工作区", "media-editor"),
            ToolDirectoryEntry("代码工作区", "代码协作与补丁审查", "coworkbench"),
            ToolDirectoryEntry("Git 工作区", "管理仓库与代码任务", "git")
        )
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("工具与扩展", color = Ink, style = MaterialTheme.typography.headlineSmall)
        Text("日常只需在对话中提出需求。这里用于管理和配置能力，不需要先选择工具才能开始聊天。", color = Muted, style = MaterialTheme.typography.bodySmall)
        groups.forEach { (heading, entries) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heading, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 6.dp))
                entries.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onNavigate(entry.destination) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.title, color = Ink, style = MaterialTheme.typography.titleMedium)
                            Text(entry.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }
}
