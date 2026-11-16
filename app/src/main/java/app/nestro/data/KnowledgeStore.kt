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
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

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
  require(ext in setOf("txt","md","markdown","html","htm","csv","json","xml","log","docx","xlsx","pptx") || mime.startsWith("text/") || mime=="application/json") { "当前支持 TXT、Markdown、HTML、CSV、JSON、XML、DOCX、XLSX、PPTX；PDF 文本解析暂未接入。" }
  val bytes=resolver.openInputStream(uri)?.use { input ->
   val out=ByteArrayOutputStream(); val buffer=ByteArray(8192); var total=0
   while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=MAX_FILE_BYTES) { "文件超过 2 MB，请先拆分或压缩。" }; out.write(buffer,0,n) }
   out.toByteArray()
  } ?: error("无法读取所选文件")
  val raw=bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
  val parsed=when {
   ext in setOf("docx","xlsx","pptx") -> extractOfficeText(ext, bytes)
   ext in setOf("html","htm") || mime.contains("html") -> Html.fromHtml(raw,Html.FROM_HTML_MODE_COMPACT).toString()
   else -> raw
  }
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
  val terms=KnowledgeSearchEngine.extractTerms(prompt)
  if(terms.isEmpty()) return@withContext ""
  val candidates=linkedMapOf<String,KnowledgeChunkEntity>()
  terms.forEach { term ->
   dao.searchKnowledgeChunks(projectId,term,12).forEach { chunk -> candidates.putIfAbsent(chunk.id,chunk) }
  }
  val ranked=KnowledgeSearchEngine.rank(prompt,candidates.values.toList(),6)
  if(ranked.isEmpty()) return@withContext ""
  val docs=dao.knowledgeDocuments(projectId).associateBy { it.id }
  val snippets=ranked.joinToString("\n\n") { match ->
   val item=match.chunk
   val source=docs[item.documentId]?.name ?: "本地文档"
   "[来源：$source · 片段 ${item.position+1} · 匹配词：${match.matchedTerms.joinToString("、")} ]\n${item.content.take(1200)}"
  }.take(7000)
  "以下内容来自当前项目空间的本地知识库，仅在相关时参考，并在回答中标注来源：\n$snippets"
 }
 private fun extractOfficeText(ext:String,bytes:ByteArray):String {
  val entries=linkedMapOf<String,ByteArray>()
  var expanded=0
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
   var entry=zip.nextEntry
   var count=0
   while(entry!=null) {
    count++
    require(count<=1200) { "Office 文件包含过多压缩条目。" }
    val name=entry.name
    val shouldRead=when(ext) {
     "docx" -> name=="word/document.xml"
     "xlsx" -> name=="xl/sharedStrings.xml" || Regex("xl/worksheets/sheet[0-9]+\\.xml").matches(name)
     else -> Regex("ppt/slides/slide[0-9]+\\.xml").matches(name)
    }
    val out=if(shouldRead && !entry.isDirectory) ByteArrayOutputStream() else null
    val buffer=ByteArray(8192)
    while(true) {
     val n=zip.read(buffer)
     if(n<0) break
     expanded+=n
     require(expanded<=8*1024*1024) { "Office 文件解压后超过 8 MB。" }
     out?.write(buffer,0,n)
    }
    if(out!=null) entries[name]=out.toByteArray()
    entry=zip.nextEntry
   }
  }
  return when(ext) {
   "docx" -> {
    val xml=entries["word/document.xml"] ?: error("DOCX 文件缺少正文内容。")
    val document=parseXml(xml)
    val paragraphs=document.getElementsByTagNameNS("*","p")
    buildString {
     for(i in 0 until paragraphs.length) {
      val paragraph=paragraphs.item(i) as? Element ?: continue
      val texts=paragraph.getElementsByTagNameNS("*","t")
      val line=(0 until texts.length).joinToString("") { texts.item(it).textContent.orEmpty() }
      if(line.isNotBlank()) append(line).append('\n')
     }
    }
   }
   "xlsx" -> {
    val shared=entries["xl/sharedStrings.xml"]?.let { xml ->
     val document=parseXml(xml)
     val items=document.getElementsByTagNameNS("*","si")
     (0 until items.length).map { index ->
      val item=items.item(index) as Element
      val texts=item.getElementsByTagNameNS("*","t")
      (0 until texts.length).joinToString("") { texts.item(it).textContent.orEmpty() }
     }
    }.orEmpty()
    val sheets=entries.filterKeys { Regex("xl/worksheets/sheet[0-9]+\\.xml").matches(it) }
     .toSortedMap(compareBy { it.substringAfter("sheet").substringBefore(".").toIntOrNull() ?: 0 })
    require(sheets.isNotEmpty()) { "XLSX 文件没有可读取的工作表。" }
    buildString {
     sheets.forEach { (sheetName,xml) ->
      append("工作表：").append(sheetName.substringAfterLast('/')).append('\n')
      val document=parseXml(xml)
      val rows=document.getElementsByTagNameNS("*","row")
      for(r in 0 until rows.length) {
       val cells=(rows.item(r) as Element).getElementsByTagNameNS("*","c")
       val values=mutableListOf<String>()
       for(c in 0 until cells.length) {
        val cell=cells.item(c) as Element
        val type=cell.getAttribute("t")
        val value=when(type) {
         "s" -> {
          val index=(cell.getElementsByTagNameNS("*","v").item(0)?.textContent ?: "").toIntOrNull()
          index?.let { shared.getOrNull(it) }.orEmpty()
         }
         "inlineStr" -> {
          val textNodes=cell.getElementsByTagNameNS("*","t")
          (0 until textNodes.length).joinToString("") { textNodes.item(it).textContent.orEmpty() }
         }
         else -> cell.getElementsByTagNameNS("*","v").item(0)?.textContent.orEmpty()
        }
        if(value.isNotBlank()) values += cell.getAttribute("r") + "=" + value
       }
       if(values.isNotEmpty()) append(values.joinToString(" | ")).append('\n')
      }
     }
    }
   }
   else -> {
    val slides=entries.filterKeys { Regex("ppt/slides/slide[0-9]+\\.xml").matches(it) }
     .toSortedMap(compareBy { it.substringAfter("slide").substringBefore(".").toIntOrNull() ?: 0 })
    require(slides.isNotEmpty()) { "PPTX 文件没有可读取的幻灯片。" }
    buildString {
     slides.forEach { (slideName,xml) ->
      append("幻灯片：").append(slideName.substringAfterLast('/')).append('\n')
      val document=parseXml(xml)
      val paragraphs=document.getElementsByTagNameNS("*","p")
      for(p in 0 until paragraphs.length) {
       val texts=(paragraphs.item(p) as Element).getElementsByTagNameNS("*","t")
       val line=(0 until texts.length).joinToString("") { texts.item(it).textContent.orEmpty() }
       if(line.isNotBlank()) append(line).append('\n')
      }
     }
    }
   }
  }.trim()
 }

 private fun parseXml(bytes:ByteArray):Document {
  val factory=DocumentBuilderFactory.newInstance().apply {
   isNamespaceAware=true
   isXIncludeAware=false
   isExpandEntityReferences=false
   setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
   setFeature("http://xml.org/sax/features/external-general-entities",false)
   setFeature("http://xml.org/sax/features/external-parameter-entities",false)
  }
  return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
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
