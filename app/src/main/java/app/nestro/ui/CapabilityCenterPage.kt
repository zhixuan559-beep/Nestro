package app.nestro.ui

import android.content.SharedPreferences
import app.nestro.data.CapabilityRegistryStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CapabilityEntry(
    val id: String, val title: String, val group: String, val description: String,
    val status: String, val detail: String, val route: String? = null,
    val defaultGranted: Boolean = true, val available: Boolean = true
)

@Composable
fun CapabilityCenterPage(
    prefs: SharedPreferences, projectId: String, activeModel: String, modelReady: Boolean,
    expertCount: Int, teamCount: Int, skillCount: Int, enabledSkillCount: Int,
    workflowCount: Int, onNavigate: (String) -> Unit
) {
    val mint = Color(0xFF34D399); val ink = Color(0xFFE8EAED); val muted = Color(0xFF8A8F98)
    val card = Color(0xFF1A1C20); val line = Color(0xFF2A2D33); val warning = Color(0xFFFBBF24)
    var query by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("全部") }
    var refresh by remember { mutableIntStateOf(0) }; var pending by remember { mutableStateOf<CapabilityEntry?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    val auditRecords = remember(projectId, refresh, showAudit) { CapabilityRegistryStore.recentAudit(prefs, projectId) }
    val entries = listOf(
        CapabilityEntry("models","模型服务","基础能力","管理模型服务商、API 配置、主模型切换与故障切换。",if(modelReady)"已配置" else "未配置",if(modelReady)activeModel else "尚未启用可用模型配置","models"),
        CapabilityEntry("experts","专家系统","智能协作","管理专家角色、提示词、模板与版本。","已接入","$expertCount 个专家配置","experts"),
        CapabilityEntry("teams","专家团队","智能协作","组合多个专家并行分析，由主模型汇总结果。","已接入","$teamCount 个团队","teams"),
        CapabilityEntry("skills","Skills 技能","智能协作","管理可复用技能、权限声明、导入导出与启停。","已接入","$enabledSkillCount / $skillCount 个技能已启用","skills"),
        CapabilityEntry("workflows","工作流","任务编排","将提示词、专家和技能组合成连续任务步骤。","已接入","$workflowCount 条工作流","workflows"),
        CapabilityEntry("knowledge","本地知识库","上下文","导入资料、项目隔离检索，并向对话提供可追溯上下文。","已接入","按当前项目独立检索","knowledge"),
        CapabilityEntry("memory","长期记忆","上下文","管理全局记忆与项目记忆，支持来源追溯和授权共享。","已接入","全局与项目级记忆","memory"),
        CapabilityEntry("phone","Phone Agent","设备执行","通过 Android 无障碍能力执行受控手机操作，敏感动作交由用户确认。","基础能力","仍受系统权限与应用可访问性限制","phone"),
        CapabilityEntry("mcp","MCP 工具接入","外部工具","统一接入本地或远程 MCP 服务，并管理逐工具授权、调用预览与审计。","待接入","当前版本尚未提供 MCP 连接器",defaultGranted=false,available=false),
        CapabilityEntry("github","GitHub 工作台","开发工具","连接代码仓库，浏览文件、审查变更并执行受控开发操作。","待接入","当前版本尚未提供仓库连接器",defaultGranted=false,available=false)
    )
    val grants = entries.associate { it.id to CapabilityRegistryStore.isGranted(prefs,projectId,it.id,it.defaultGranted) }
    val visible = entries.filter {
        val q=query.isBlank() || it.title.contains(query,true) || it.description.contains(query,true) || it.group.contains(query,true)
        val g=grants[it.id]==true
        q && when(filter) { "已授权"->g; "未授权"->!g; "未接入"->!it.available; else->true }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("统一能力中心",color=ink,style=MaterialTheme.typography.headlineSmall)
        Text("统一发现、授权与进入 Nestro 能力。授权按项目隔离；外部连接器未接入时不会显示为可调用。",color=muted,style=MaterialTheme.typography.bodySmall)
        Surface(color=card,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,line),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text("当前项目权限",color=ink,fontWeight=FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text(projectId,color=muted,style=MaterialTheme.typography.labelSmall) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Metric("已授权",entries.count{it.available&&grants[it.id]==true}.toString(),mint,Modifier.weight(1f))
                    Metric("未接入",entries.count{!it.available}.toString(),warning,Modifier.weight(1f))
                    Metric("能力项",entries.size.toString(),ink,Modifier.weight(1f))
                }
                Text("撤销授权只阻止从本入口进入，不删除数据；设备操作等高风险动作仍由对应模块二次确认。",color=muted,style=MaterialTheme.typography.labelSmall)
            }
        }
        OutlinedTextField(query,{query=it.take(60)},Modifier.fillMaxWidth(),singleLine=true,label={Text("搜索能力")})
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("全部","已授权","未授权","未接入").forEach { FilterChip(filter==it,{filter=it},label={Text(it)}) } }
        visible.forEach { e ->
            val granted=grants[e.id]==true
            Surface(color=card,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,line),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(e.title,color=ink,fontWeight=FontWeight.SemiBold); Text(e.group,color=muted,style=MaterialTheme.typography.labelSmall) }
                        Text(if(!e.available)"未接入" else if(!granted)"未授权" else e.status,color=if(!e.available||!granted)warning else mint,style=MaterialTheme.typography.labelMedium)
                    }
                    Text(e.description,color=muted,style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(e.detail,color=muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f))
                        if(e.available) Switch(granted,{checked->
                            CapabilityRegistryStore.setGranted(prefs,projectId,e.id,checked)
                            CapabilityRegistryStore.record(prefs,projectId,e.id,e.title,if(checked)"grant" else "revoke",if(checked)"success" else "revoked")
                            refresh++
                        })
                    }
                    if(e.available) Text(if(granted)"预览并进入 →" else "需先授权",color=if(granted)mint else muted,style=MaterialTheme.typography.labelMedium,
                        modifier=Modifier.clickable(enabled=granted){ CapabilityRegistryStore.record(prefs,projectId,e.id,e.title,"preview","pending_confirmation");refresh++;pending=e })
                    else Text("连接器尚未实现，不能授权或调用。",color=warning,style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("最近访问审计",color=ink,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            TextButton(onClick={showAudit=!showAudit;refresh++}) { Text(if(showAudit)"收起" else "查看",color=mint) }
        }
        if(showAudit) {
            if(auditRecords.isEmpty()) Text("当前项目暂无记录。",color=muted,style=MaterialTheme.typography.bodySmall)
            auditRecords.forEach { r ->
                Column(Modifier.padding(vertical=3.dp)) {
                    Text("${r.capabilityName} · ${r.action} · ${r.outcome}",color=ink,style=MaterialTheme.typography.bodySmall)
                    Text(SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(Date(r.createdAt)),color=muted,style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("授权和访问记录保存在本机，按项目隔离；审计不记录 API Key、文件正文或工具参数。",color=muted,style=MaterialTheme.typography.labelSmall)
    }
    pending?.let { e ->
        AlertDialog(onDismissRequest={pending=null},title={Text("进入前确认")},
            text={Text("即将打开「${e.title}」。本次只打开模块，不会自动执行设备操作、外部调用或高风险动作。")},
            confirmButton={TextButton(onClick={
                CapabilityRegistryStore.record(prefs,projectId,e.id,e.title,"open_module","dispatched")
                refresh++;pending=null;e.route?.let(onNavigate)
            }){Text("确认进入")}},
            dismissButton={TextButton(onClick={pending=null}){Text("取消")}})
    }
}

@Composable private fun Metric(label:String,value:String,color:Color,modifier:Modifier=Modifier) {
    Column(modifier) { Text(value,color=color,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(label,color=Color(0xFF8A8F98),style=MaterialTheme.typography.labelSmall) }
}
