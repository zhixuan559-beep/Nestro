package app.nestro.ui

import app.nestro.NestroApplication
import app.nestro.mcp.McpEndpointPolicy
import app.nestro.mcp.McpServerRecord
import app.nestro.mcp.McpToolArgumentValidator
import app.nestro.mcp.McpToolDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun McpToolCenterPage(app:NestroApplication) {
 val repository=remember(app){app.mcpToolCenterRepository}
 val service=remember(app){app.mcpToolCenterService}
 val scope=rememberCoroutineScope()
 var servers by remember{mutableStateOf(repository.listServers())}
 var tools by remember{mutableStateOf(repository.listTools())}
 var serverName by remember{mutableStateOf("")}
 var endpoint by remember{mutableStateOf("")}
 var bearerToken by remember{mutableStateOf("")}
 var status by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(false)}
 var selectedTool by remember{mutableStateOf<McpToolDescriptor?>(null)}
 var argumentsJson by remember{mutableStateOf("{}")}
 var deleteServer by remember{mutableStateOf<McpServerRecord?>(null)}
 fun reload(){servers=repository.listServers();tools=repository.listTools()}
 fun discover(id:String){
  scope.launch{
   loading=true;status="正在连接 MCP 服务并读取工具目录…"
   val result=runCatching{service.discover(id)}
   result.fold({status="发现完成，共 ${it.size} 个工具。已有授权状态已保留。"},{status="连接或发现失败："+(it.message?:"未知错误")})
   reload();loading=false
  }
 }
 LaunchedEffect(Unit){service.restoreEnabledTools();reload()}
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("MCP 工具中心",style=MaterialTheme.typography.headlineSmall)
  Text("连接支持 Streamable HTTP 的远程 MCP 服务。服务地址仅接受 HTTPS；不会在手机上启动或运行任意本地 MCP Server。工具发现不等于授权，必须逐个授权后才会进入任务工具目录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text("添加远程服务",style=MaterialTheme.typography.titleMedium)
  OutlinedTextField(serverName,{serverName=it},label={Text("服务名称")},modifier=Modifier.fillMaxWidth(),singleLine=true)
  OutlinedTextField(endpoint,{endpoint=it},label={Text("MCP Streamable HTTP HTTPS 地址")},modifier=Modifier.fillMaxWidth(),singleLine=true)
  OutlinedTextField(bearerToken,{bearerToken=it},label={Text("Bearer Token（可选）")},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
  Button(enabled=!loading&&serverName.isNotBlank()&&endpoint.isNotBlank()&&McpEndpointPolicy.validate(endpoint)==null,onClick={
   val id=UUID.randomUUID().toString()
   try{
    repository.saveServer(McpServerRecord(id,serverName.trim(),endpoint.trim()))
    if(bearerToken.isNotBlank())repository.saveCredential(id,bearerToken.trim())
    serverName="";endpoint="";bearerToken="";reload();discover(id)
   }catch(e:Exception){status="保存失败："+(e.message?:"未知错误")}
  },modifier=Modifier.fillMaxWidth()){Text("保存并发现工具")}
  if(endpoint.isNotBlank()&&McpEndpointPolicy.validate(endpoint)!=null)Text(McpEndpointPolicy.validate(endpoint).orEmpty(),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
  HorizontalDivider()
  Text("已连接服务（${servers.size}）",style=MaterialTheme.typography.titleMedium)
  if(servers.isEmpty())Text("还没有添加 MCP 服务。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  servers.forEach{server->
   Card(Modifier.fillMaxWidth()){
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
      Column(Modifier.weight(1f)){Text(server.name,style=MaterialTheme.typography.titleSmall);Text(server.endpoint,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
      Switch(checked=server.enabled,onCheckedChange{on->
       runCatching{service.setServerEnabled(server.id,on)}.onFailure{status="服务状态更新失败："+(it.message?:"未知错误")}
       reload()
      })
     }
     Text(if(repository.hasCredential(server.id))"Bearer Token：已加密保存" else "Bearer Token：未配置",style=MaterialTheme.typography.labelSmall)
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
      OutlinedButton(enabled=!loading,onClick={discover(server.id)}){Text("刷新工具")}
      if(repository.hasCredential(server.id))TextButton(onClick={
       runCatching{repository.clearCredential(server.id)}.onSuccess{status="凭据已清除。"}.onFailure{status="凭据清除失败："+(it.message?:"未知错误")};reload()
      }){Text("清除凭据")}
      TextButton(onClick={deleteServer=server}){Text("删除服务",color=MaterialTheme.colorScheme.error)}
     }
    }
   }
  }
  HorizontalDivider()
  Text("已发现工具（${tools.size}）",style=MaterialTheme.typography.titleMedium)
  if(tools.isEmpty())Text("连接服务并刷新后，工具会显示在这里。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  tools.forEach{tool->
   Card(Modifier.fillMaxWidth()){
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
      Column(Modifier.weight(1f)){
       Text(tool.name,style=MaterialTheme.typography.titleSmall)
       Text(tool.serverName+" · "+if(tool.readOnlyHint)"服务声明只读" else "写入/未知权限",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Switch(checked=tool.granted,enabled=repository.findServer(tool.serverId)?.enabled==true,onCheckedChange{on->
       runCatching{service.setToolGranted(tool.id,on)}.onFailure{status="工具授权更新失败："+(it.message?:"未知错误")};reload()
      })
     }
     Text(tool.description.ifBlank{"服务未提供工具说明"},style=MaterialTheme.typography.bodySmall)
     Text(if(tool.granted)"已授权：可供任务按需调用" else "未授权：不会注册到任务工具目录",style=MaterialTheme.typography.labelSmall)
     OutlinedButton(enabled=tool.granted&&!loading,onClick{selectedTool=tool;argumentsJson="{}"},modifier=Modifier.fillMaxWidth()){Text("调用预览")}
    }
   }
  }
 }
 selectedTool?.let{tool->
  val validation=McpToolArgumentValidator.validate(tool.inputSchemaJson,argumentsJson)
  AlertDialog(onDismissRequest{selectedTool=null},title{Text("调用前预览：${tool.name}")},text{
   Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text("服务："+tool.serverName,style=MaterialTheme.typography.bodySmall)
    Text(tool.description,style=MaterialTheme.typography.bodySmall)
    Text("参数 Schema",style=MaterialTheme.typography.labelLarge)
    Text(tool.inputSchemaJson.take(4000),style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(value=argumentsJson,onValueChange{argumentsJson=it},label{Text("本次调用参数 JSON")},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=10)
    if(validation!=null)Text(validation,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    Text("每次调用都会展示参数预览；服务端返回内容视为不可信数据，不会自动获得其他工具或设备权限。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  },confirmButton{
   TextButton(enabled=validation==null&&!loading,onClick{
    val id=tool.id;val args=argumentsJson;selectedTool=null
    scope.launch{
     loading=true;status="正在调用 "+tool.name+"…"
     val result=runCatching{service.invoke(id,args)}
     result.fold({v->status=if(v.success)"调用成功：\n"+v.output else "调用失败："+v.error},{e->status="调用失败："+(e.message?:"未知错误")})
     loading=false;reload()
    }
   }){Text("确认并调用")}
  },dismissButton{TextButton(onClick{selectedTool=null}){Text("取消")}})
 }
 deleteServer?.let{server->
  AlertDialog(onDismissRequest{deleteServer=null},title{Text("删除 MCP 服务？")},
   text{Text("删除“${server.name}”会同时删除其工具目录、授权状态和本地加密凭据。此操作不可撤销。")},
   confirmButton{TextButton(onClick{
    runCatching{service.deleteServer(server.id)}.onSuccess{status="服务及关联凭据已删除。"}.onFailure{status="删除失败："+(it.message?:"未知错误")}
    deleteServer=null;reload()
   }){Text("确认删除",color=MaterialTheme.colorScheme.error)}},
   dismissButton{TextButton(onClick{deleteServer=null}){Text("取消")}})
 }
}
