package app.nestro.ui

import android.content.SharedPreferences
import app.nestro.data.CapabilityDescriptor
import app.nestro.data.CapabilityRegistryStore
import app.nestro.data.NestroDao
import app.nestro.data.CapabilityTaskEntity
import app.nestro.data.CapabilityTaskState
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

@Composable
fun CapabilityCenterPage(
    prefs: SharedPreferences,
    dao: NestroDao,
    projectId: String,
    onDispatchTask: (String) -> Unit,
    onPauseTask: (CapabilityTaskEntity) -> Unit,
    onResumeTask: (CapabilityTaskEntity) -> Unit,
    onCancelTask: (CapabilityTaskEntity) -> Unit,
    activeModel: String,
    modelReady: Boolean,
    expertCount: Int,
    teamCount: Int,
    skillCount: Int,
    enabledSkillCount: Int,
    workflowCount: Int,
    onNavigate: (String) -> Unit
) {
    val mint=Color(0xFF34D399); val ink=Color(0xFFE8EAED); val muted=Color(0xFF8A8F98)
    val card=Color(0xFF1A1C20); val line=Color(0xFF2A2D33); val warning=Color(0xFFFBBF24)
    var query by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("全部") }
    var refresh by remember { mutableIntStateOf(0) }; var pending by remember { mutableStateOf<CapabilityDescriptor?>(null) }
    var taskDraft by remember { mutableStateOf("") }; var showAudit by remember { mutableStateOf(false) }
    var taskRows by remember { mutableStateOf(emptyList<CapabilityTaskEntity>()) }
    LaunchedEffect(projectId, refresh) { taskRows = dao.capabilityTasks(projectId, 50) }

    val builtIns=listOf(
        CapabilityDescriptor("models","模型服务","基础能力","管理模型服务商、API 配置、主模型切换与故障切换。",status=if(modelReady)"已配置" else "未配置",detail=if(modelReady)activeModel else "尚未启用可用模型配置",route="models"),
        CapabilityDescriptor("experts","专家系统","智能协作","管理专家角色、提示词、模板与版本。",detail="$expertCount 个专家配置",route="experts"),
        CapabilityDescriptor("teams","专家团队","智能协作","组合多个专家并行分析，由主模型汇总结果。",detail="$teamCount 个团队",route="teams"),
        CapabilityDescriptor("skills","Skills 技能","智能协作","管理可复用技能、权限声明、导入导出与启停。",detail="$enabledSkillCount / $skillCount 个技能已启用",route="skills"),
        CapabilityDescriptor("workflows","工作流","任务编排","将提示词、专家和技能组合成连续任务步骤。",detail="$workflowCount 条工作流",route="workflows"),
        CapabilityDescriptor("knowledge","本地知识库","上下文","导入资料、项目隔离检索，并向对话提供可追溯上下文。",detail="按当前项目独立检索",route="knowledge"),
        CapabilityDescriptor("memory","长期记忆","上下文","管理全局记忆与项目记忆，支持来源追溯和授权共享。",detail="全局与项目级记忆",route="memory"),
        CapabilityDescriptor("phone","Phone Agent","设备执行","通过 Android 无障碍能力执行受控手机操作，敏感动作交由用户确认。",status="基础能力",detail="仍受系统权限与应用可访问性限制",route="phone"),
        CapabilityDescriptor("mcp","MCP 工具接入","外部工具","统一接入本地或远程 MCP 服务，并管理逐工具授权、调用预览与审计。",status="待接入",detail="连接器尚未实现",available=false,defaultGranted=false),
        CapabilityDescriptor("github","GitHub 工作台","开发工具","连接代码仓库，浏览文件、审查变更并执行受控开发操作。",status="待接入",detail="连接器尚未实现",available=false,defaultGranted=false)
    )
    LaunchedEffect(builtIns) { CapabilityRegistryStore.registerAll(prefs,builtIns); refresh++ }
    val registry=remember(refresh,projectId) { CapabilityRegistryStore.list(prefs) }
    val grants=registry.associate { it.id to CapabilityRegistryStore.isGranted(prefs,projectId,it.id,it.defaultGranted) }
    val audit=remember(projectId,refresh,showAudit) { CapabilityRegistryStore.recentAudit(prefs,projectId) }
    val visible=registry.filter { item ->
        val matches=query.isBlank() || item.title.contains(query,true) || item.description.contains(query,true) || item.category.contains(query,true)
        val allowed=grants[item.id]==true
        matches && when(filter) { "已授权"->allowed; "未授权"->!allowed; "未接入"->!item.available; else->true }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("统一能力中心",color=ink,style=MaterialTheme.typography.headlineSmall)
        Text("统一发现、授权与调度 Nestro 能力。目录支持模块注册；授权按项目隔离，未接入的外部连接器不会显示为可调用。",color=muted,style=MaterialTheme.typography.bodySmall)
        Surface(color=card,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,line),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text("当前项目能力",color=ink,fontWeight=FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text(projectId,color=muted,style=MaterialTheme.typography.labelSmall) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Metric("已授权",registry.count{it.available&&grants[it.id]==true}.toString(),mint,Modifier.weight(1f))
                    Metric("未接入",registry.count{!it.available}.toString(),warning,Modifier.weight(1f))
                    Metric("注册能力",registry.size.toString(),ink,Modifier.weight(1f))
                }
            }
        }
        OutlinedTextField(query,{query=it.take(60)},Modifier.fillMaxWidth(),singleLine=true,label={Text("搜索能力")},placeholder={Text("例如：模型、MCP、技能")})
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("全部","已授权","未授权","未接入").forEach { FilterChip(filter==it,{filter=it},label={Text(it)}) } }
        visible.forEach { item ->
            val granted=grants[item.id]==true
            Surface(color=card,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,line),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.title,color=ink,fontWeight=FontWeight.SemiBold); Text(item.category,color=muted,style=MaterialTheme.typography.labelSmall) }
                        Text(if(!item.available)"未接入" else if(!granted)"未授权" else item.status,color=if(!item.available||!granted)warning else mint,style=MaterialTheme.typography.labelMedium)
                    }
                    Text(item.description,color=muted,style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(item.detail,color=muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f))
                        if(item.available) Switch(granted,{checked->
                            CapabilityRegistryStore.setGranted(prefs,projectId,item.id,checked)
                            CapabilityRegistryStore.record(prefs,projectId,item.id,item.title,if(checked)"grant" else "revoke",if(checked)"success" else "revoked")
                            refresh++
                        })
                    }
                    if(item.available) Text(if(granted)"预览并进入 →" else "需先授权",color=if(granted)mint else muted,style=MaterialTheme.typography.labelMedium,
                        modifier=Modifier.clickable(enabled=granted){ CapabilityRegistryStore.record(prefs,projectId,item.id,item.title,"preview","pending_confirmation");refresh++;pending=item })
                    else Text("连接器尚未实现，当前不能授权或调用。",color=warning,style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        Surface(color=card,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,line),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("统一任务入口",color=ink,fontWeight=FontWeight.SemiBold)
                Text("把目标交给主对话，由当前模型结合项目上下文处理；此入口不会绕过各模块原有权限或自动执行高风险操作。",color=muted,style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(taskDraft,{taskDraft=it.take(2000)},Modifier.fillMaxWidth(),minLines=2,label={Text("描述你要完成的任务")})
                Button(onClick={
                    val prompt="请从统一能力入口处理以下任务：${taskDraft.trim()}。当前已选择的专家、团队和技能会按项目授权策略生效，知识库与记忆只在获准时检索；如任务需要尚未接入的外部连接器或其他模块操作，请明确说明，不得假称已执行。涉及设备操作或不可逆动作时先请求确认。"
                    CapabilityRegistryStore.record(prefs,projectId,"dispatcher","统一任务入口","dispatch_to_chat","handed_to_main_chat")
                    onDispatchTask(prompt)
                },enabled=taskDraft.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("带入主对话") }
            }
        }
        Surface(color=card,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,line),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("持久任务队列",color=ink,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${taskRows.size} 条",color=muted,style=MaterialTheme.typography.labelSmall)
                }
                Text("统一入口提交的任务会先写入本机数据库，再交给主对话处理；切换页面或重启应用后仍可查看任务状态与运行事件。",color=muted,style=MaterialTheme.typography.bodySmall)
                if(taskRows.isEmpty()) Text("当前项目暂无任务。",color=muted,style=MaterialTheme.typography.bodySmall)
                taskRows.forEach { task ->
                    val stateLabel = when(task.state) {
                        CapabilityTaskState.QUEUED -> "排队中"
                        CapabilityTaskState.RUNNING -> "执行中"
                        CapabilityTaskState.PAUSED -> "已暂停"
                        CapabilityTaskState.WAITING_USER -> "等待用户处理"
                        CapabilityTaskState.RETRY_WAIT -> "等待重试"
                        CapabilityTaskState.SUCCEEDED -> "已完成"
                        CapabilityTaskState.FAILED -> "失败"
                        CapabilityTaskState.CANCELLED -> "已取消"
                        else -> task.state
                    }
                    Column(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(task.title,color=ink,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                            Text(stateLabel,color=if(task.state==CapabilityTaskState.SUCCEEDED) mint else if(task.state==CapabilityTaskState.FAILED) warning else muted,style=MaterialTheme.typography.labelSmall)
                        }
                        Text(task.input.take(180),color=muted,style=MaterialTheme.typography.bodySmall,maxLines=2)
                        if(task.error.isNotBlank()) Text(task.error,color=warning,style=MaterialTheme.typography.labelSmall,maxLines=2)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(task.state==CapabilityTaskState.QUEUED || task.state==CapabilityTaskState.RUNNING) {
                                TextButton(onClick={ onPauseTask(task); refresh++ }) { Text("暂停") }
                            }
                            if(task.state==CapabilityTaskState.PAUSED || task.state==CapabilityTaskState.WAITING_USER) {
                                TextButton(onClick={ onResumeTask(task); refresh++ }) { Text("继续") }
                            }
                            if(!CapabilityTaskState.isTerminal(task.state)) {
                                TextButton(onClick={ onCancelTask(task); refresh++ }) { Text("取消",color=warning) }
                            }
                        }
                    }
                    Divider(color=line)
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("最近授权与访问审计",color=ink,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            TextButton(onClick={showAudit=!showAudit;refresh++}) { Text(if(showAudit)"收起" else "查看记录",color=mint) }
        }
        if(showAudit) {
            if(audit.isEmpty()) Text("当前项目暂无记录。",color=muted,style=MaterialTheme.typography.bodySmall)
            audit.forEach { record ->
                Column(Modifier.padding(vertical=3.dp)) {
                    Text("${record.capabilityName} · ${record.action} · ${record.outcome}",color=ink,style=MaterialTheme.typography.bodySmall)
                    Text(SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(Date(record.createdAt)),color=muted,style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("刷新能力目录",color=muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f))
            TextButton(onClick={CapabilityRegistryStore.registerAll(prefs,builtIns);refresh++}) { Text("刷新",color=mint) }
        }
        Text("权限与审计保存在本机并按项目隔离；日志不记录 API Key、文件正文或完整任务内容。",color=muted,style=MaterialTheme.typography.labelSmall)
    }
    pending?.let { item ->
        AlertDialog(onDismissRequest={pending=null},title={Text("进入前确认")},
            text={Text("即将打开「${item.title}」。本次仅进入模块，不会自动执行设备操作、外部调用或高风险动作。") },
            confirmButton={TextButton(onClick={
                CapabilityRegistryStore.record(prefs,projectId,item.id,item.title,"open_module","dispatched")
                refresh++;pending=null;item.route?.let(onNavigate)
            }){Text("确认进入")}},
            dismissButton={TextButton(onClick={pending=null}){Text("取消")}})
    }
}

@Composable private fun Metric(label:String,value:String,color:Color,modifier:Modifier=Modifier) {
    Column(modifier) { Text(value,color=color,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold); Text(label,color=Color(0xFF8A8F98),style=MaterialTheme.typography.labelSmall) }
}
