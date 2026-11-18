package app.nestro.ui
import app.nestro.data.KnowledgeDocumentEntity
import app.nestro.data.KnowledgeSearchResult
import app.nestro.data.KnowledgeRepository
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
private val KnowledgeCard=androidx.compose.ui.graphics.Color(0xFF1A1C20)
private val KnowledgeInk=androidx.compose.ui.graphics.Color(0xFFE8EAED)
private val KnowledgeMuted=androidx.compose.ui.graphics.Color(0xFF8A8F98)
private val KnowledgeMint=androidx.compose.ui.graphics.Color(0xFF34D399)
@Composable
fun KnowledgePage(repository:KnowledgeRepository,projectId:String,readableProjectIds:Set<String> = emptySet(),writableProjectIds:Set<String> = emptySet()) {
 val context=LocalContext.current; val scope=rememberCoroutineScope()
 val latestProjectId by rememberUpdatedState(projectId)
 val documents=remember { mutableStateListOf<KnowledgeDocumentEntity>() }
 var status by remember { mutableStateOf("文档保存在本机，并按项目空间隔离。") }
 var loading by remember { mutableStateOf(false) }
 var pendingDelete by remember { mutableStateOf<KnowledgeDocumentEntity?>(null) }
 var searchQuery by remember { mutableStateOf("") }
 var searching by remember { mutableStateOf(false) }
 val searchResults=remember { mutableStateListOf<KnowledgeSearchResult>() }
 suspend fun refresh(targetProjectId:String=projectId) {
  val projectIds=(listOf(targetProjectId)+if(targetProjectId==projectId) readableProjectIds else emptySet()).distinct()
  val rows=projectIds.flatMap { repository.documents(it) }
  if(latestProjectId!=targetProjectId) return
  documents.clear(); documents.addAll(rows)
 }
 LaunchedEffect(projectId,readableProjectIds) {
  documents.clear()
  searchResults.clear()
  searchQuery=""
  pendingDelete=null
  status="正在读取当前项目的本地知识库…"
  runCatching { refresh(projectId) }.onFailure { status="读取知识库失败：${it.message ?: "未知错误"}" }
 }
 val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri!=null) {
   loading=true; status="正在读取并建立本地索引…"
   val requestedProjectId=projectId
   scope.launch {
    val result=runCatching { repository.importText(context.contentResolver,uri,requestedProjectId) }
    if(latestProjectId==requestedProjectId) {
     result.onSuccess { refresh(requestedProjectId); status=if(it.duplicate) "这份文档已存在，无需重复导入。" else "已导入「${it.document.name}」，可在当前项目对话中检索。" }
      .onFailure { status="导入失败：${it.message ?: "未知错误"}" }
    }
    loading=false
   }
  }
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Text("本地知识库",color=KnowledgeInk,style=MaterialTheme.typography.headlineSmall)
  Text("当前空间：$projectId。已授权读取的项目知识库也会显示；写入授权可管理对应项目的文档。资料保存在本机 Room 数据库。",color=KnowledgeMuted,style=MaterialTheme.typography.bodySmall)
  Button(onClick={launcher.launch(arrayOf("text/*","application/json","application/xml","application/octet-stream","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/pdf"))},enabled=!loading,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=KnowledgeMint,contentColor=androidx.compose.ui.graphics.Color(0xFF0F1115))) { Text(if(loading) "处理中…" else "＋ 导入文档") }
  Text("支持 TXT、Markdown、HTML、CSV、JSON、XML、DOCX、XLSX、PPTX、PDF。单文件上限 2 MB，Office 文件解压后上限 8 MB。PDF 文本提取后进入本地索引。",color=KnowledgeMuted,style=MaterialTheme.typography.bodySmall)
  Text(status,color=KnowledgeMuted,style=MaterialTheme.typography.bodySmall)
  OutlinedTextField(
   value=searchQuery,
   onValueChange={searchQuery=it},
   modifier=Modifier.fillMaxWidth(),
   label={Text("检索测试")},
   placeholder={Text("输入问题，查看实际命中的文档片段")},
   maxLines=2,
   enabled=!searching
  )
  Button(
   onClick={
    searching=true
    val requestedProjectId=projectId
    val requestedQuery=searchQuery
    scope.launch {
     val result=runCatching {
      (listOf(requestedProjectId)+readableProjectIds).distinct()
       .flatMap { sourceProjectId -> repository.search(sourceProjectId,requestedQuery,6) }
       .take(6)
     }
     if(latestProjectId==requestedProjectId) {
      result.onSuccess {
       searchResults.clear(); searchResults.addAll(it)
       status=if(it.isEmpty()) "没有找到匹配片段，可以换一种关键词表达。" else "找到 ${it.size} 个相关片段，下面展示实际命中内容。"
      }.onFailure { status="检索失败：${it.message ?: "未知错误"}" }
     }
     searching=false
    }
   },
   enabled=!searching && searchQuery.isNotBlank() && documents.isNotEmpty(),
   modifier=Modifier.fillMaxWidth()
  ) { Text(if(searching) "正在检索…" else "测试知识库检索") }
  if(searchResults.isNotEmpty()) {
   Text("检索命中",color=KnowledgeInk,style=MaterialTheme.typography.titleMedium)
   searchResults.forEach { result ->
    Surface(color=KnowledgeCard,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
     Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
      Text(result.documentName,color=KnowledgeInk,style=MaterialTheme.typography.titleSmall)
      Text("片段 ${result.chunkPosition+1} · 匹配词：${result.matchedTerms.joinToString("、")}",color=KnowledgeMint,style=MaterialTheme.typography.labelSmall)
      Text(result.content.take(700),color=KnowledgeMuted,style=MaterialTheme.typography.bodySmall)
     }
    }
   }
  }
  if(documents.isEmpty()) Text("这里还没有文档。导入资料后，Nestro 会在当前项目内检索，不会混入其他项目。",color=KnowledgeMuted)
  else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
   documents.forEach { doc ->
    Surface(color=KnowledgeCard,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
     Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
       Text(doc.name,color=KnowledgeInk,style=MaterialTheme.typography.titleMedium)
       Text("${doc.sizeBytes/1024} KB · ${SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(doc.importedAt))}",color=KnowledgeMuted,style=MaterialTheme.typography.bodySmall)
      }
      IconButton(onClick={pendingDelete=doc},enabled=(doc.projectId==projectId || doc.projectId in writableProjectIds)) {
       Icon(Icons.Outlined.Delete,contentDescription="删除文档",tint=androidx.compose.ui.graphics.Color(0xFFFF7777))
      }
     }
    }
   }
  }
 }
 pendingDelete?.let { target ->
  AlertDialog(
   onDismissRequest={pendingDelete=null},
   title={Text("删除这份文档？")},
   text={Text("「"+target.name+"」及其全部索引片段都会从所属项目「"+target.projectId+"」删除，且无法撤销。")},
   confirmButton={
    TextButton(onClick={
     pendingDelete=null
     val requestedProjectId=projectId
     scope.launch {
      val result=runCatching { repository.deleteDocument(target.id,target.projectId) }
      if(latestProjectId==requestedProjectId) {
       result.onSuccess {
        documents.removeAll { it.id==target.id }
        searchResults.clear()
        status="已删除文档及其全部索引片段。"
       }.onFailure { status="删除失败：${it.message ?: "未知错误"}" }
      }
     }
    }) { Text("删除",color=androidx.compose.ui.graphics.Color(0xFFFF7777)) }
   },
   dismissButton={TextButton(onClick={pendingDelete=null}) { Text("取消") }}
  )
 }
}
