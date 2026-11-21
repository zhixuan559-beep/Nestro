package app.nestro.mcp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.isActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicLong

data class McpRemoteTool(val name:String,val description:String,val inputSchema:JSONObject,val readOnlyHint:Boolean)
private data class McpHttpReply(val code:Int,val body:String,val contentType:String,val sessionId:String?)
object McpHeaderValueEncoder {
 fun encode(value:String):String {
  val plainAscii=value.all{it.code in 0x21..0x7e}&&!value.startsWith("=?base64?")
  return if(plainAscii)value else "=?base64?"+Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))+"?="
 }
}
private object McpHttpClientProvider {
 val client:OkHttpClient=OkHttpClient.Builder()
  .connectTimeout(12,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS)
  .writeTimeout(20,TimeUnit.SECONDS).callTimeout(75,TimeUnit.SECONDS)
  .followRedirects(false).followSslRedirects(false).build()
}
class McpStreamableHttpClient(endpoint:String,private val bearerToken:String) : AutoCloseable {
 private val url=McpEndpointPolicy.requireValid(endpoint)
 private val client=McpHttpClientProvider.client
 private val ids=AtomicLong(0)
 private var protocolVersion="2026-07-28"
 private var legacyMode=false
 private var initialized=false
 private var sessionId:String?=null

 suspend fun listTools():List<McpRemoteTool> {
  val all=mutableListOf<McpRemoteTool>();var cursor:String?=null
  repeat(10){
   val params=JSONObject()
   cursor?.let{params.put("cursor",it)}
   if(!legacyMode)params.put("_meta",clientMeta())
   val result=rpc("tools/list",params).optJSONObject("result")?:error("MCP tools/list 缺少 result")
   val items=result.optJSONArray("tools")?:JSONArray()
   for(i in 0 until items.length()){
    val item=items.optJSONObject(i)?:continue;val name=item.optString("name")
    require(name.isNotBlank()&&name.length<=128&&name.none{it.isISOControl()}){"MCP 返回了无效工具名称"}
    val schema=item.optJSONObject("inputSchema")?:JSONObject().put("type","object").put("properties",JSONObject())
    require(schema.optString("type","object")=="object"){"暂不支持非 object 工具参数 schema：$name"}
    require(schema.toString().length<=64_000){"MCP 工具 schema 超过 64 KB 限制：$name"}
    all+=McpRemoteTool(name,item.optString("description").take(500),schema,item.optJSONObject("annotations")?.optBoolean("readOnlyHint",false)==true)
    require(all.size<=200){"MCP 服务工具数量超过 200"}
   }
   cursor=result.optString("nextCursor").takeIf{it.isNotBlank()}
   if(cursor==null)return all.distinctBy{it.name}.also{require(it.size==all.size){"MCP 服务返回了重复工具名称"}}
  }
  require(cursor==null){"MCP 工具目录分页超过 10 页"};return all
 }

 suspend fun callTool(name:String,arguments:JSONObject,inputSchemaJson:String):String {
  val params=JSONObject().put("name",name).put("arguments",arguments)
  if(!legacyMode)params.put("_meta",clientMeta())
  val result=rpc("tools/call",params,name,inputSchemaJson).optJSONObject("result")?:error("MCP tools/call 缺少 result")
  if(result.optBoolean("isError",false))error(extractContent(result.optJSONArray("content")).ifBlank{"MCP 工具执行返回错误"}.take(2000))
  val structured=result.optJSONObject("structuredContent")
  return (structured?.toString()?:extractContent(result.optJSONArray("content"))).ifBlank{"工具执行完成（服务未返回文本内容）"}.take(32_000)
 }

 private suspend fun rpc(method:String,params:JSONObject,name:String?=null,schemaJson:String="{}"):JSONObject {
  if(!legacyMode){
   val id=ids.incrementAndGet()
   val request=JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params)
   val reply=post(request,method,name,schemaJson,true,true)
   val parsed=reply.body.takeIf{it.isNotBlank()}?.let{parseBody(it,reply.contentType)}
   if(reply.code !in 200..299){
    val rpcError=parsed?.optJSONObject("error");val message=rpcError?.optString("message").orEmpty()
    val errorData=rpcError?.optJSONObject("data")
    val supported=errorData?.optJSONArray("supported")?:errorData?.optJSONArray("supportedVersions")
    if(reply.code==400&&(message.contains("protocol",true)||message.contains("unsupported",true)||supported!=null)){
     legacyMode=true;return rpc(method,params,name,schemaJson)
    }
    if((reply.code==400||reply.code==404)&&parsed==null){legacyMode=true;return rpc(method,params,name,schemaJson)}
    error("MCP HTTP "+reply.code+"："+(message.ifBlank{reply.body.take(1000)}))
   }
   val response=parsed?:error("MCP 服务没有返回 JSON-RPC 响应")
   if(response.has("error")){val e=response.optJSONObject("error")?:JSONObject();error("MCP $method 失败（"+e.optInt("code")+"）："+e.optString("message","未知错误"))}
   require(response.opt("id")?.toString()==id.toString()){"MCP JSON-RPC 响应 ID 不匹配"};return response
  }
  initializeLegacy()
  val id=ids.incrementAndGet()
  val reply=post(JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params),method,name,schemaJson,false,true)
  require(reply.code in 200..299){"MCP HTTP "+reply.code+"："+reply.body.take(1000)}
  val json=parseBody(reply.body,reply.contentType)?:error("MCP 服务没有返回 JSON-RPC 响应")
  if(json.has("error")){val e=json.optJSONObject("error")?:JSONObject();error("MCP $method 失败（"+e.optInt("code")+"）："+e.optString("message","未知错误"))}
  require(json.opt("id")?.toString()==id.toString()){"MCP JSON-RPC 响应 ID 不匹配"};return json
 }

 private suspend fun initializeLegacy(){
  if(initialized)return
  var lastError="MCP 初始化失败"
  for(version in listOf("2025-11-25","2025-06-18","2025-03-26")){
   protocolVersion=version
   val id=ids.incrementAndGet()
   val request=JSONObject().put("jsonrpc","2.0").put("id",id).put("method","initialize")
    .put("params",JSONObject().put("protocolVersion",version).put("capabilities",JSONObject()).put("clientInfo",JSONObject().put("name","Nestro").put("version","0.3.1")))
   val reply=post(request,"initialize",null,"{}",false,true)
   val json=runCatching{parseBody(reply.body,reply.contentType)}.getOrNull()
   val result=json?.optJSONObject("result")
   if(reply.code in 200..299&&result!=null){
    val negotiated=result.optString("protocolVersion",version)
    require(negotiated in listOf("2025-11-25","2025-06-18","2025-03-26")){"MCP 服务返回了不支持的协议版本："+negotiated}
    protocolVersion=negotiated
    val notified=post(JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),"notifications/initialized",null,"{}",false,false)
    require(notified.code in 200..299){"MCP 初始化通知失败：HTTP "+notified.code}
    initialized=true;return
   }
   lastError=json?.optJSONObject("error")?.optString("message")?:"MCP 初始化 HTTP "+reply.code
  }
  error(lastError)
 }

 override fun close() {
  val id=sessionId ?: return
  if(legacyMode) runCatching {
   val builder=Request.Builder().url(url).delete().header("Accept","application/json, text/event-stream")
   if(protocolVersion!="2025-03-26")builder.header("MCP-Protocol-Version",protocolVersion)
   builder.header("Mcp-Session-Id",id)
   if(bearerToken.isNotBlank())builder.header("Authorization","Bearer $bearerToken")
   client.newCall(builder.build()).execute().close()
  }
  sessionId=null
 }

 private fun clientMeta()=JSONObject()
  .put("io.modelcontextprotocol/protocolVersion","2026-07-28")
  .put("io.modelcontextprotocol/clientInfo",JSONObject().put("name","Nestro").put("version","0.3.1"))
  .put("io.modelcontextprotocol/clientCapabilities",JSONObject())

 private suspend fun post(payload:JSONObject,method:String,name:String?,schemaJson:String,modern:Boolean,expectResponse:Boolean):McpHttpReply =
  suspendCancellableCoroutine { continuation ->
   val builder=Request.Builder().url(url).post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).header("Accept","application/json, text/event-stream")
   if(modern||protocolVersion!="2025-03-26")builder.header("MCP-Protocol-Version",protocolVersion)
   if(modern){
    builder.header("Mcp-Method",method)
    name?.let{builder.header("Mcp-Name",McpHeaderValueEncoder.encode(it))}
    if(method=="tools/call"&&name!=null)addSchemaHeaders(builder,schemaJson,payload.optJSONObject("params")?.optJSONObject("arguments")?:JSONObject())
   }
   if(bearerToken.isNotBlank())builder.header("Authorization","Bearer $bearerToken")
   if(!modern)sessionId?.let{builder.header("Mcp-Session-Id",it)}
   val call=client.newCall(builder.build())
   continuation.invokeOnCancellation { call.cancel() }
   call.enqueue(object:okhttp3.Callback {
    override fun onFailure(call:okhttp3.Call,error:java.io.IOException) {
     if(continuation.isActive)continuation.resumeWithException(error)
    }
    override fun onResponse(call:okhttp3.Call,response:okhttp3.Response) {
     try {
      val reply=response.use {
       if(!modern)it.header("Mcp-Session-Id")?.let{session->require(session.length<=512);sessionId=session}
       val body=readBounded(it.body?.byteStream(),1_000_000)
       McpHttpReply(it.code,body,it.header("Content-Type").orEmpty(),it.header("Mcp-Session-Id"))
      }
      if(continuation.isActive)continuation.resume(reply)
     } catch(error:Exception) {
      if(continuation.isActive)continuation.resumeWithException(error)
     }
    }
   })
  }

 private fun addSchemaHeaders(builder:Request.Builder,schemaJson:String,args:JSONObject){
  val schema=runCatching{JSONObject(schemaJson)}.getOrNull()?:return
  fun walk(properties:JSONObject?,values:JSONObject){
   if(properties==null)return
   val keys=properties.keys()
   while(keys.hasNext()){
    val key=keys.next();val property=properties.optJSONObject(key)?:continue;val value=values.opt(key)
    val header=property.optString("x-mcp-header")
    if(header.isNotBlank()&&!values.isNull(key)){
     require(header.matches(Regex("[A-Za-z0-9-]{1,48}"))){"MCP 工具 schema 含无效 x-mcp-header"}
     builder.header("Mcp-Param-$header",McpHeaderValueEncoder.encode(value.toString()))
    }
    if(value is JSONObject)walk(property.optJSONObject("properties"),value)
   }
  }
  walk(schema.optJSONObject("properties"),args)
 }

 private fun parseBody(raw:String,contentType:String):JSONObject?{
  if(raw.isBlank())return null
  return if(contentType.contains("text/event-stream",true)){
   val messages=raw.split("\n\n","\r\n\r\n").mapNotNull{event->
    val data=event.lineSequence().filter{it.startsWith("data:")}.map{it.removePrefix("data:").trimStart()}.joinToString("\n")
    data.takeIf{it.isNotBlank()}?.let{runCatching{JSONObject(it)}.getOrNull()}
   }
   messages.lastOrNull{it.has("id")}?:messages.lastOrNull()
  }else runCatching{JSONObject(raw)}.getOrNull()
 }
 private fun extractContent(items:JSONArray?):String{
  if(items==null)return ""
  return (0 until items.length()).mapNotNull{i->
   val item=items.optJSONObject(i)?:return@mapNotNull null
   when(item.optString("type")){"text"->item.optString("text");"image"->"[服务返回了图片内容，当前页面暂不显示]";"resource"->item.optJSONObject("resource")?.optString("text");else->item.optString("text").takeIf{it.isNotBlank()}}
  }.joinToString("\n")
 }
 private fun readBounded(stream:java.io.InputStream?,limit:Int):String{
  if(stream==null)return "";val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
  stream.use{input->while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=limit){"MCP 响应超过 "+(limit/1024)+" KB 限制"};out.write(buffer,0,n)}}
  return String(out.toByteArray(),Charsets.UTF_8)
 }
}
