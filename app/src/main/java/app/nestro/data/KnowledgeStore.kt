package app.nestro.data
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

data class KnowledgeImportResult(val document: KnowledgeDocumentEntity, val duplicate: Boolean)
object KnowledgeStore {
 private const val MAX_FILE_BYTES=2*1024*1024
 private const val MAX_TEXT_CHARS=500_000
 private const val CHUNK_SIZE=1_000
 private const val CHUNK_OVERLAP=120
 private const val MAX_CHUNKS=600

 suspend fun importText(resolver:ContentResolver,uri:Uri,projectId:String,dao:NestroDao):KnowledgeImportResult=withContext(Dispatchers.IO) {
  val mime=resolver.getType(uri).orEmpty().lowercase()
  val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "导入文档"
  val ext=name.substringAfterLast('.',"").lowercase()
  require(ext in setOf("txt","md","markdown","html","htm","csv","json","xml","log") || mime.startsWith("text/") || mime=="application/json") { "当前版本支持 TXT、Markdown、HTML、CSV、JSON、XML 等文本文件；PDF/Office 解析正在开发。" }
  val bytes=resolver.openInputStream(uri)?.use { input ->
   val out=ByteArrayOutputStream(); val buffer=ByteArray(8192); var total=0
   while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=MAX_FILE_BYTES) { "文件超过 2 MB，请先拆分或压缩。" }; out.write(buffer,0,n) }
   out.toByteArray()
  } ?: error("无法读取所选文件")
  val raw=bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
  val parsed=if(ext in setOf("html","htm") || mime.contains("html")) Html.fromHtml(raw,Html.FROM_HTML_MODE_COMPACT).toString() else raw
  val normalized=parsed.replace("\u0000","").replace("\r\n","\n").trim()
  require(normalized.isNotBlank()) { "文件没有可索引的文本内容。" }
  require(normalized.length<=MAX_TEXT_CHARS) { "文本超过 50 万字符，请拆分文件后导入。" }
  val hash=MessageDigest.getInstance("SHA-256").digest((projectId+"\u0000"+normalized).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  val existing=dao.knowledgeDocumentByHash(hash)
  if(existing!=null) return@withContext KnowledgeImportResult(existing,true)
  val document=KnowledgeDocumentEntity(UUID.randomUUID().toString(),projectId,name.take(180),mime.ifBlank { "text/plain" },bytes.size.toLong(),System.currentTimeMillis(),hash)
  val chunks=split(normalized).mapIndexed { i,part -> KnowledgeChunkEntity(UUID.randomUUID().toString(),document.id,projectId,i,part) }
  require(chunks.isNotEmpty()) { "无法生成有效文档片段。" }
  val saved=dao.insertKnowledgeBundleIfAbsent(document,chunks)
  KnowledgeImportResult(saved,saved.id!=document.id)
 }

 suspend fun retrieve(dao:NestroDao,projectId:String,prompt:String):String=withContext(Dispatchers.IO) {
  val candidates=linkedSetOf<String>()
  prompt.lowercase().split(Regex("[\\\\s，。！？；：、,.!?;:（）()]+" )).forEach { segment ->
   Regex("[a-z0-9][a-z0-9_-]{1,31}").findAll(segment).forEach { candidates+=it.value }
   val cjk=segment.filter { ch -> ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF }
   for(size in listOf(4,3,2)) {
    var offset=0
    while(offset+size<=cjk.length) { candidates+=cjk.substring(offset,offset+size); offset+=if(size==4) 3 else 1 }
   }
  }
  val ignored=setOf("帮我","请帮","一下","这个","那个","进行","关于","如何","可以","里面","内容","文档","怎么","什么","然后","现在","之前")
  val terms=candidates.filter { it.length>=2 && it !in ignored }.take(16)
  if(terms.isEmpty()) return@withContext ""
  val scores=linkedMapOf<String,Pair<KnowledgeChunkEntity,Int>>()
  terms.forEach { term ->
   val safe=term.replace("%","").replace("_","").replace("\\\\","")
   if(safe.length>=2) dao.searchKnowledgeChunks(projectId,safe,8).forEach { chunk ->
    val old=scores[chunk.id]
    scores[chunk.id]=chunk to ((old?.second ?: 0)+1)
   }
  }
  val ranked=scores.values.sortedWith(compareByDescending<Pair<KnowledgeChunkEntity,Int>> { it.second }.thenBy { it.first.position }).take(6)
  if(ranked.isEmpty()) return@withContext ""
  val docs=dao.knowledgeDocuments(projectId).associateBy { it.id }
  val snippets=ranked.joinToString("\\n\\n") { (item,score) ->
   val source=docs[item.documentId]?.name ?: "本地文档"
   "[来源：$source · 片段 ${item.position+1} · 匹配度 $score]\\n${item.content.take(1200)}"
  }.take(7000)
  "以下内容来自当前项目空间的本地知识库，仅在相关时参考，并在回答中标注来源：\\n$snippets"
 }
 private fun split(text:String):List<String> {
  val result=ArrayList<String>(); var start=0
  while(start<text.length && result.size<MAX_CHUNKS) {
   var end=minOf(start+CHUNK_SIZE,text.length)
   if(end<text.length) { val boundary=text.lastIndexOfAny(charArrayOf('\n','。','！','？','.', '!', '?'),end); if(boundary>start+CHUNK_SIZE/2) end=boundary+1 }
   val part=text.substring(start,end).trim(); if(part.isNotBlank()) result+=part
   if(end>=text.length) break
   start=maxOf(start+1,end-CHUNK_OVERLAP)
  }
  return result
 }
}
