package app.nestro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class CapabilityEntry(
    val name: String,
    val description: String,
    val source: String,
    val permission: String,
    val state: String
)

@Composable
fun CapabilityDirectoryPage() {
    var query by remember { mutableStateOf("") }
    val entries = remember {
        listOf(
            CapabilityEntry("模型连接", "连接用户自行配置的 OpenAI 兼容模型服务。", "Nestro 内置", "网络访问；API Key 由 Android Keystore 保护", "已内置"),
            CapabilityEntry("语音输入", "调用 Android 系统语音识别，将识别结果回填输入框。", "Android 系统服务", "麦克风运行时权限", "已内置"),
            CapabilityEntry("语音输出", "使用系统 TTS 朗读文本，支持中文音色与语速设置。", "Android 系统 TTS", "无额外敏感权限", "已内置"),
            CapabilityEntry("本地知识库", "导入文本与 Office 文档，进行项目内检索。", "Nestro 本地模块", "用户主动选择文件", "已内置"),
            CapabilityEntry("专家与团队", "使用本地专家配置和并行团队分析任务。", "Nestro 本地模块", "按模型配置发送请求", "已内置"),
            CapabilityEntry("Skills", "管理本地技能包；导入内容只解析文本与声明，不执行任意代码。", "本地技能包", "导入前预览；权限声明不自动授权", "已内置"),
            CapabilityEntry("工作流", "按顺序执行提示词、专家和技能节点。", "Nestro 本地模块", "按节点实际调用能力", "已内置"),
            CapabilityEntry("Phone Agent", "在用户授权下读取可见界面并执行受限动作。", "Android Accessibility", "无障碍授权；高风险动作由用户接手", "已内置"),
            CapabilityEntry("产物库", "按项目保存 Markdown 成果，支持版本、预览、分享和导出。", "Nestro 本地存储", "文件保存在应用私有目录", "已内置")
        )
    }
    val filtered = entries.filter { entry ->
        query.isBlank() || listOf(entry.name, entry.description, entry.source).any { it.contains(query, true) }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("能力目录", style = MaterialTheme.typography.headlineSmall)
        Text("展示当前版本可用能力、来源和权限边界。这里只列出已核实的本地能力，不会把未接入的社区服务伪装成可安装插件。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索能力") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.name }) { item ->
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(item.state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(item.description, style = MaterialTheme.typography.bodySmall)
                        Text("来源：${item.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("权限：${item.permission}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("社区目录、远程来源签名校验、版本更新和安装授权尚未接入。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
