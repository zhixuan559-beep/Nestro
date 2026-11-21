package app.nestro.mcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class McpRemoteTool(val name:String,val description:String,val inputSchema:JSONObject,val readOnlyHint:Boolean)
class McpStreamableHttpClient(endpoint:String,private val bearerToken:String){
 private val url=McpEndpointPolicy.requireValid(endpoint)
 private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS).callTimeout(75,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
 private val ids=AtomicLong(0);private var sessionId:String?=null;private var protocolVersion="2025-03-26";private var initialized=false
 fun listTools():List<McpRemoteTool>{
  initialize();val all=mutableListOf<McpRemoteTool>();var cursor:String?=null
  repeat(10){
   val params=JSONObject().apply{cursor?.let{put("cursor",it)}}
   val result=rpc("tools/list",params).optJSONObject("result")?:error("MCP tools/list 缺少 result")
   val items=result.optJSONArray("tools")?:JSONArray()
   for(i in 0 until items.length()){
    val item=items.optJSONObject(i)?:continue;val name=item.optString("name")
    require(name.matches(Regex("[A-Za-z0-9_.-]{1,128}"))){"MCP 返回了无效工具名称"}
    val schema=item.optJSONObject("inputSchema")?:JSONObject().put("type","object").put("properties",JSONObject())
    require(schema.optString("type","object")=="object"){"暂不支持非 object 工具参数 schema：$name"}
    all+=McpRemoteTool(name,item.optString("description").take(500),schema,item.optJSONObject("annotations")?.optBoolean("readOnlyHint",false)==true)
    require(all.size<=200){"MCP 服务工具数量超过 200"}
   }
   cursor=result.optString("nextCursor").takeIf{it.isNotBlank()}
   if(cursor==null)return all.distinctBy{it.name}.also{require(it.size==all.size){"MCP 服务返回了重复工具名称"}}
  }
  require(cursor==null){"MCP 工具目录分页超过 10 页"};return all
 }
 fun callTool(name:String,args:JSONObject):String{
  initialize();val result=rpc("tools/call",JSONObject().put("name",name).put("arguments",args)).optJSONObject("result")?:error("MCP tools/call 缺少 result")
  if(result.optBoolean("isError",false))error(extractContent(result.optJSONArray("content")).ifBlank{"MCP 工具执行返回错误"}.take(2000))
  val structured=result.optJSONObject("structuredContent")
  return (structured?.toString()?:extractContent(result.optJSONArray("content"))).ifBlank{"工具执行完成（服务未返回文本内容）"}.take(32_000)
 }
 private fun initialize(){
  if(initialized)return
  val result=rpc("initialize",JSONObject().put("protocolVersion",protocolVersion).put("capabilities",JSONObject()).put("clientInfo",JSONObject().put("name","Nestro").put("version","0.3.1"))).optJSONObject("result")?:error("MCP 初始化响应缺少 result")
  val negotiated=result.optString("protocolVersion")
  require(negotiated in setOf("2025-03-26","2024-11-05")){"MCP 服务协议版本不兼容：$negotiated"}
  protocolVersion=negotiated
  post(JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),false);initialized=true
 }
 private fun rpc(method:String,params:JSONObject):JSONObject{
  val id=ids.incrementAndGet()
  val response=post(JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params),true)?:error("MCP 服务没有返回 JSON-RPC 响应")
  if(response.has("error")){val e=response.optJSONObject("error")?:JSONObject();error("MCP $method 失败（"+e.optInt("code")+"）："+e.optString("message","未知错误"))}
  require(response.opt("id")?.toString()==id.toString()){"MCP JSON-RPC 响应 ID 不匹配"};return response
 }
 private fun post(payload:JSONObject,expectResponse:Boolean):JSONObject?{
  val builder=Request.Builder().url(url).post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).header("Accept","application/json, text/event-stream")
  if(initialized)builder.header("MCP-Protocol-Version",protocolVersion)
  if(bearerToken.isNotBlank())builder.header("Authorization","Bearer $bearerToken")
  sessionId?.let{builder.header("Mcp-Session-Id",it)}
  client.newCall(builder.build()).execute().use{response->
   response.header("Mcp-Session-Id")?.let{require(it.length<=512);sessionId=it}
   if(!expectResponse&&response.code==202)return null
   if(response.code !in 200..299){val detail=readBounded(response.body?.byteStream(),8192);error("MCP HTTP "+response.code+"："+detail.take(1000))}
   val raw=readBounded(response.body?.byteStream(),1_000_000);if(!expectResponse)return null
   return if(response.header("Content-Type").orEmpty().contains("text/event-stream",true))parseSse(raw) else JSONObject(raw)
  }
 }
 private fun parseSse(raw:String):JSONObject{
  val messages=raw.lineSequence().filter{it.startsWith("data:")}.map{it.removePrefix("data:").trim()}.filter{it.isNotBlank()}.mapNotNull{runCatching{JSONObject(it)}.getOrNull()}.toList()
  return messages.lastOrNull{it.has("id")}?:messages.lastOrNull()?:error("MCP SSE 响应中没有 JSON-RPC 消息")
 }
 private fun extractContent(items:JSONArray?):String{
  if(items==null)return ""
  return (0 until items.length()).mapNotNull{i->val o=items.optJSONObject(i)?:return@mapNotNull null
   when(o.optString("type")){"text"->o.optString("text");"image"->"[服务返回了图片内容，当前页面暂不显示]";"resource"->o.optJSONObject("resource")?.optString("text");else->o.optString("text").takeIf{it.isNotBlank()}}
  }.joinToString("\n")
 }
 private fun readBounded(stream:java.io.InputStream?,limit:Int):String{
  if(stream==null)return "";val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
  stream.use{input->while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=limit){"MCP 响应超过 "+(limit/1024)+" KB 限制"};out.write(buffer,0,n)}}
  return String(out.toByteArray(),Charsets.UTF_8)
 }
}
