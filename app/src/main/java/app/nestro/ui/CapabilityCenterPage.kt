package app.nestro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class CapabilityEntry(
    val title: String,
    val group: String,
    val description: String,
    val status: String,
    val detail: String,
    val route: String? = null
)

@Composable
fun CapabilityCenterPage(
    activeModel: String,
    modelReady: Boolean,
    expertCount: Int,
    teamCount: Int,
    skillCount: Int,
    enabledSkillCount: Int,
    workflowCount: Int,
    onNavigate: (String) -> Unit
) {
    val mint = Color(0xFF34D399)
    val ink = Color(0xFFE8EAED)
    val muted = Color(0xFF8A8F98)
    val card = Color(0xFF1A1C20)
    val line = Color(0xFF2A2D33)
    val warning = Color(0xFFFBBF24)
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }

    val entries = listOf(
        CapabilityEntry("模型服务", "基础能力", "管理模型服务商、API 配置、主模型切换与故障切换。",
            if (modelReady) "已配置" else "未配置", if (modelReady) activeModel else "尚未启用可用模型配置", "models"),
        CapabilityEntry("专家系统", "智能协作", "管理专家角色、提示词、模板与版本。",
            "已接入", "$expertCount 个专家配置", "experts"),
        CapabilityEntry("专家团队", "智能协作", "组合多个专家并行分析，由主模型汇总结果。",
            "已接入", "$teamCount 个团队", "teams"),
        CapabilityEntry("Skills 技能", "智能协作", "管理可复用技能、权限声明、导入导出与启停。",
            "已接入", "$enabledSkillCount / $skillCount 个技能已启用", "skills"),
        CapabilityEntry("工作流", "任务编排", "将提示词、专家和技能组合成连续任务步骤。",
            "已接入", "$workflowCount 条工作流", "workflows"),
        CapabilityEntry("本地知识库", "上下文", "导入资料、项目隔离检索，并向对话提供可追溯上下文。",
            "已接入", "按当前项目独立检索", "knowledge"),
        CapabilityEntry("长期记忆", "上下文", "管理全局记忆与项目记忆，支持来源追溯和授权共享。",
            "已接入", "全局与项目级记忆", "memory"),
        CapabilityEntry("Phone Agent", "设备执行", "通过 Android 无障碍能力执行受控手机操作，敏感动作交由用户确认。",
            "基础能力", "仍受系统权限与应用可访问性限制", "phone"),
        CapabilityEntry("MCP 工具接入", "外部工具", "统一接入本地或远程 MCP 服务，并管理逐工具授权、调用预览与审计。",
            "待接入", "当前版本尚未提供 MCP 连接器"),
        CapabilityEntry("GitHub 工作台", "开发工具", "连接代码仓库，浏览文件、审查变更并执行受控开发操作。",
            "待接入", "当前版本尚未提供仓库连接器")
    )
    val visible = entries.filter { entry ->
        val matchesQuery = query.isBlank() || entry.title.contains(query, true) ||
            entry.description.contains(query, true) || entry.group.contains(query, true)
        val matchesFilter = when (filter) {
            "已接入" -> entry.status == "已接入" || entry.status == "已配置" || entry.status == "基础能力"
            "待接入" -> entry.status == "待接入" || entry.status == "未配置"
            else -> true
        }
        matchesQuery && matchesFilter
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("统一能力中心", color = ink, style = MaterialTheme.typography.headlineSmall)
        Text("Nestro 的能力总入口。这里集中查看当前已接入的模型、专家、技能、工作流与设备能力；未接入的外部工具会明确标记，不会伪装成可用。",
            color = muted, style = MaterialTheme.typography.bodySmall)
        Surface(color = card, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, line), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前能力概况", color = ink, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryMetric("已接入", entries.count { it.status in setOf("已接入", "已配置", "基础能力") }.toString(), mint, Modifier.weight(1f))
                    SummaryMetric("待接入", entries.count { it.status == "待接入" }.toString(), warning, Modifier.weight(1f))
                    SummaryMetric("总能力项", entries.size.toString(), ink, Modifier.weight(1f))
                }
            }
        }
        OutlinedTextField(value = query, onValueChange = { query = it.take(60) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("搜索能力") }, placeholder = { Text("例如：模型、MCP、技能") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "已接入", "待接入").forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
            }
        }
        if (visible.isEmpty()) Text("没有找到匹配的能力。", color = muted, modifier = Modifier.padding(vertical = 20.dp))
        else visible.forEach { entry ->
            val statusColor = when (entry.status) {
                "待接入", "未配置" -> warning
                "基础能力" -> Color(0xFF93C5FD)
                else -> mint
            }
            Surface(color = card, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, line),
                modifier = Modifier.fillMaxWidth().clickable(enabled = entry.route != null) { entry.route?.let(onNavigate) }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.title, color = ink, fontWeight = FontWeight.SemiBold)
                            Text(entry.group, color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(entry.status, color = statusColor, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(entry.description, color = muted, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.detail, color = muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        if (entry.route != null) Text("进入管理  →", color = mint, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("能力中心目前负责统一发现与进入各能力模块。MCP、GitHub 等外部连接器尚未接入；逐工具授权、统一调用审计与跨模块任务调度仍属于后续建设范围。",
            color = muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFF8A8F98), style = MaterialTheme.typography.labelSmall)
    }
}
