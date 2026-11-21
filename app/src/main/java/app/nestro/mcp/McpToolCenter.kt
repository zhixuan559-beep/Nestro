package app.nestro.mcp
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.nestro.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class McpServerRecord(val id:String,val name:String,val endpoint:String,val enabled:Boolean=true)
data class McpToolDescriptor(val id:String,val serverId:String,val serverName:String,val name:String,val description:String,val inputSchemaJson:String,val readOnlyHint:Boolean,val granted:Boolean=false)
data class McpToolAuditRecord(val id:String,val serverName:String,val toolName:String,val succeeded:Boolean,val createdAt:Long)

object McpEndpointPolicy {
 fun validate(endpoint:String):String? {
  if(endpoint.isBlank()||endpoint.length>2048)return "MCP 地址不能为空且不能超过 2048 个字符"
  if(endpoint.any { it.isWhitespace() })return "MCP 地址不能包含空白字符"
  val uri=runCatching { URI(endpoint.trim()) }.getOrNull()?:return "MCP 地址格式无效"
  if(!uri.isAbsolute||!uri.scheme.equals("https",true)||uri.host.isNullOrBlank())return "仅允许有效的 HTTPS 远程 MCP 地址"
  if(uri.rawUserInfo!=null||uri.rawQuery!=null||uri.rawFragment!=null)return "地址不能包含用户名、密码、查询参数或片段"
  if(uri.port !in -1..65535)return "MCP 地址端口无效"
  return null
 }
 fun requireValid(endpoint:String):String { val v=endpoint.trim(); require(validate(v)==null){validate(v)?:"MCP 地址无效"}; return v }
}
object McpToolPermissionPolicy { fun permission(readOnlyHint:Boolean)=if(readOnlyHint) AgentToolPermission.READ_ONLY else AgentToolPermission.EXTERNAL_WRITE }
object McpToolArgumentValidator {
 fun validate(schemaJson:String,argumentsJson:String):String? {
  if(argumentsJson.length>32_000)return "工具参数超过 32 KB"
  val args=runCatching { JSONObject(argumentsJson) }.getOrNull()?:return "工具参数必须是合法 JSON 对象"
  val schema=runCatching { JSONObject(schemaJson) }.getOrNull()?:return "服务返回的参数 schema 无效"
  if(schema.optString("type").isNotBlank()&&schema.optString("type")!="object")return "当前仅支持 JSON object 类型的工具参数"
  val props=schema.optJSONObject("properties")?:JSONObject(); val required=schema.optJSONArray("required")?:JSONArray()
  for(i in 0 until required.length()){val k=required.optString(i);if(k.isBlank()||!args.has(k)||args.isNull(k))return "缺少必填参数：$k"}
  if(!schema.optBoolean("additionalProperties",true)){val it=args.keys();while(it.hasNext()){val k=it.next();if(!props.has(k))return "不允许未声明的参数：$k"}}
  val it=args.keys()
  while(it.hasNext()){
   val k=it.next();val p=props.optJSONObject(k)?:continue;val v=args.opt(k);val type=p.optString("type")
   val valid=when(type){"string"->v is String;"number"->v is Number;"integer"->v is Number&&v.toDouble()%1.0==0.0;"boolean"->v is Boolean;"object"->v is JSONObject;"array"->v is JSONArray;else->true}
   if(!valid)return "参数 $k 类型不匹配，要求 $type"
   if(v is String&&p.has("maxLength")&&v.length>p.optInt("maxLength",Int.MAX_VALUE))return "参数 $k 超过长度限制"
   if(v is String&&p.has("enum")){val values=p.optJSONArray("enum")?:JSONArray();if((0 until values.length()).none{values.opt(it)==v})return "参数 $k 不在允许值范围内"}
  }
  return null
 }
}
class McpToolCenterRepository(context:Context) {
 private val app=context.applicationContext
 private val prefs=app.getSharedPreferences("nestro_local",Context.MODE_PRIVATE)
 fun listServers()=McpToolCenterCodec.decodeServers(prefs.getString(SERVERS_KEY,"[]").orEmpty())
 fun findServer(id:String)=listServers().firstOrNull{it.id==id}
 fun saveServer(s:McpServerRecord){
  require(s.id.matches(Regex("[A-Za-z0-9_.-]{1,100}")));require(s.name.isNotBlank()&&s.name.length<=80)
  val v=listServers().filterNot{it.id==s.id}+s.copy(name=s.name.trim(),endpoint=McpEndpointPolicy.requireValid(s.endpoint))
  check(prefs.edit().putString(SERVERS_KEY,McpToolCenterCodec.encodeServers(v)).commit()){"MCP 服务配置保存失败"}
 }
 fun setServerEnabled(id:String,on:Boolean){
  val v=listServers();require(v.any{it.id==id}){"MCP 服务不存在"}
  check(prefs.edit().putString(SERVERS_KEY,McpToolCenterCodec.encodeServers(v.map{if(it.id==id)it.copy(enabled=on)else it})).commit()){"MCP 服务状态保存失败"}
 }
 fun deleteServer(id:String){
  check(prefs.edit().putString(SERVERS_KEY,McpToolCenterCodec.encodeServers(listServers().filterNot{it.id==id}))
   .putString(TOOLS_KEY,McpToolCenterCodec.encodeTools(listTools().filterNot{it.serverId==id})).commit()){"MCP 服务删除失败"}
  McpCredentialVault.delete(app,id)
 }
 fun saveCredential(id:String,token:String)=McpCredentialVault.write(app,id,token)
 fun readCredential(id:String)=McpCredentialVault.read(app,id)
 fun clearCredential(id:String)=McpCredentialVault.delete(app,id)
 fun hasCredential(id:String)=readCredential(id).isNotBlank()
 fun listTools()=McpToolCenterCodec.decodeTools(prefs.getString(TOOLS_KEY,"[]").orEmpty())
 fun findTool(id:String)=listTools().firstOrNull{it.id==id}
 fun replaceServerTools(id:String,items:List<McpToolDescriptor>){
  require(items.size<=200&&items.all{it.serverId==id})
  val merged=listTools().filterNot{it.serverId==id}+items
  require(merged.map{it.id}.distinct().size==merged.size){"MCP 工具 ID 冲突"}
  check(prefs.edit().putString(TOOLS_KEY,McpToolCenterCodec.encodeTools(merged)).commit()){"MCP 工具目录保存失败"}
 }
 fun setToolGranted(id:String,on:Boolean){
  val v=listTools();require(v.any{it.id==id}){"MCP 工具不存在"}
  check(prefs.edit().putString(TOOLS_KEY,McpToolCenterCodec.encodeTools(v.map{if(it.id==id)it.copy(granted=on)else it})).commit()){"MCP 工具授权保存失败"}
 }
 fun isToolGranted(id:String)=findTool(id)?.let{it.granted&&findServer(it.serverId)?.enabled==true}==true
 fun listAudits():List<McpToolAuditRecord> = runCatching {
  val a=JSONArray(prefs.getString(AUDIT_KEY,"[]").orEmpty())
  (0 until a.length()).map{i->val o=a.getJSONObject(i);McpToolAuditRecord(o.getString("id"),o.getString("serverName"),o.getString("toolName"),o.optBoolean("succeeded"),o.optLong("createdAt"))}
 }.getOrDefault(emptyList())
 fun recordAudit(toolId:String,succeeded:Boolean) {
  val tool=findTool(toolId) ?: return
  val rows=(listOf(McpToolAuditRecord(UUID.randomUUID().toString(),tool.serverName,tool.name,succeeded,System.currentTimeMillis()))+listAudits()).take(100)
  val encoded=JSONArray().apply{rows.forEach{put(JSONObject().put("id",it.id).put("serverName",it.serverName).put("toolName",it.toolName).put("succeeded",it.succeeded).put("createdAt",it.createdAt))}}.toString()
  check(prefs.edit().putString(AUDIT_KEY,encoded).commit()){"MCP 调用审计保存失败"}
 }
 companion object{private const val SERVERS_KEY="mcp_servers_v1";private const val TOOLS_KEY="mcp_tools_v1";private const val AUDIT_KEY="mcp_tool_audit_v1"}
}
object McpToolCenterCodec {
 fun encodeServers(items:List<McpServerRecord>)=JSONArray().apply{items.forEach{put(JSONObject().put("id",it.id).put("name",it.name).put("endpoint",it.endpoint).put("enabled",it.enabled))}}.toString()
 fun decodeServers(raw:String):List<McpServerRecord> = runCatching{
  val a=JSONArray(raw);require(a.length()<=50)
  (0 until a.length()).map{i->val o=a.getJSONObject(i);McpServerRecord(o.getString("id"),o.getString("name"),McpEndpointPolicy.requireValid(o.getString("endpoint")),o.optBoolean("enabled",true))}
   .also{require(it.map(McpServerRecord::id).distinct().size==it.size)}
 }.getOrDefault(emptyList())
 fun encodeTools(items:List<McpToolDescriptor>)=JSONArray().apply{items.forEach{put(JSONObject().put("id",it.id).put("serverId",it.serverId).put("serverName",it.serverName).put("name",it.name).put("description",it.description).put("inputSchema",it.inputSchemaJson).put("readOnlyHint",it.readOnlyHint).put("granted",it.granted))}}.toString()
 fun decodeTools(raw:String):List<McpToolDescriptor> = runCatching{
  val a=JSONArray(raw);require(a.length()<=10_000)
  (0 until a.length()).map{i->val o=a.getJSONObject(i);val schema=o.optString("inputSchema","{}");require(schema.length<=64_000&&JSONObject(schema).optString("type","object")=="object")
   McpToolDescriptor(o.getString("id"),o.getString("serverId"),o.getString("serverName"),o.getString("name"),o.optString("description").take(500),schema,o.optBoolean("readOnlyHint",false),o.optBoolean("granted",false))}
   .also{require(it.map(McpToolDescriptor::id).distinct().size==it.size)}
 }.getOrDefault(emptyList())
 fun toolId(serverId:String,name:String):String{
  val bytes=MessageDigest.getInstance("SHA-256").digest("$serverId:$name".toByteArray(Charsets.UTF_8))
  return "mcp_"+bytes.take(20).joinToString(""){"%02x".format(it.toInt() and 255)}
 }
}
private object McpCredentialVault {
 private const val ALIAS="nestro_mcp_credentials_v1";private const val PREFIX="mcp_bearer_enc_v1_"
 fun write(c:Context,id:String,token:String){
  require(id.matches(Regex("[A-Za-z0-9_.-]{1,100}")));require(token.length<=4096)
  if(token.isBlank()){delete(c,id);return}
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  val packed=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
  check(c.getSharedPreferences("nestro_local",Context.MODE_PRIVATE).edit().putString(PREFIX+id,packed).commit()){"MCP 凭据加密保存失败"}
 }
 fun read(c:Context,id:String):String{
  require(id.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
  val packed=c.getSharedPreferences("nestro_local",Context.MODE_PRIVATE).getString(PREFIX+id,null)?:return ""
  return runCatching{val p=packed.split(":");require(p.size==2);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));String(cipher.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),Charsets.UTF_8)}.getOrDefault("")
 }
 fun delete(c:Context,id:String){
  require(id.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
  check(c.getSharedPreferences("nestro_local",Context.MODE_PRIVATE).edit().remove(PREFIX+id).commit()){"MCP 凭据删除失败"}
 }
 private fun key():SecretKey{
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(ks.getKey(ALIAS,null) as? SecretKey)?.let{return it}
  val g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
  g.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
  return g.generateKey()
 }
}
class McpToolCenterService(private val repository:McpToolCenterRepository,private val catalog:AgentToolCatalog,private val traces:AgentTaskTraceStore?=null){
 fun restoreEnabledTools(){repository.listTools().filter{it.granted&&repository.findServer(it.serverId)?.enabled==true}.forEach(::registerTool)}
 suspend fun discover(serverId:String):List<McpToolDescriptor>{
  val server=requireNotNull(repository.findServer(serverId)){"MCP 服务不存在"};require(server.enabled){"MCP 服务已停用"}
  val remote=withContext(Dispatchers.IO){McpStreamableHttpClient(server.endpoint,repository.readCredential(serverId)).listTools()}
  require(remote.size<=200){"单个 MCP 服务最多允许 200 个工具"}
  val previous=repository.listTools().filter{it.serverId==serverId}.associateBy{it.name}
  val found=remote.map{t->McpToolDescriptor(McpToolCenterCodec.toolId(serverId,t.name),serverId,server.name,t.name,t.description.take(500),t.inputSchema.toString(),t.readOnlyHint,previous[t.name]?.granted==true)}
  previous.values.filterNot{old->found.any{it.name==old.name}}.forEach{catalog.remove(it.id)}
  repository.replaceServerTools(serverId,found);found.forEach{if(it.granted)registerTool(it)else catalog.remove(it.id)}
  return found
 }
 fun setToolGranted(id:String,on:Boolean){repository.setToolGranted(id,on);val t=requireNotNull(repository.findTool(id));if(on)registerTool(t)else catalog.remove(id)}
 fun setServerEnabled(id:String,on:Boolean){repository.setServerEnabled(id,on);repository.listTools().filter{it.serverId==id}.forEach{if(on&&it.granted)registerTool(it)else catalog.remove(it.id)}}
 fun deleteServer(id:String){repository.listTools().filter{it.serverId==id}.forEach{catalog.remove(it.id)};repository.deleteServer(id)}
 suspend fun invoke(id:String,argsJson:String):AgentToolResult{
  require(repository.isToolGranted(id)){"该 MCP 工具尚未获得授权"}
  val d=requireNotNull(repository.findTool(id)){"MCP 工具不存在"}
  McpToolArgumentValidator.validate(d.inputSchemaJson,argsJson)?.let{return AgentToolResult(false,error=it)}
  val runtime=DefaultAgentRuntime(catalog,AgentPermissionRouter{_,tool->if(repository.isToolGranted(tool.id))AgentAuthorization.Allow else AgentAuthorization.Deny("MCP 工具授权已撤销")},InMemoryAgentTaskStateStore(),safeTraceStore())
  return runtime.execute(AgentToolRequest("mcp-ui-${UUID.randomUUID()}",id,mapOf("json" to argsJson)))
 }
 private fun safeTraceStore():AgentTaskTraceStore? = traces?.let { delegate ->
  object:AgentTaskTraceStore {
   override suspend fun append(trace:AgentTaskTrace) {
    val detail=when(trace.event){"tool_succeeded"->"MCP 工具执行成功";"tool_failed"->"MCP 工具执行失败";else->trace.detail.take(300)}
    delegate.append(trace.copy(detail=detail))
   }
   override suspend fun recent(taskId:String,limit:Int)=delegate.recent(taskId,limit)
  }
 }
 private fun registerTool(d:McpToolDescriptor){
  val s=repository.findServer(d.serverId)?:return
  if(!s.enabled||!d.granted){catalog.remove(d.id);return}
  catalog.register(AgentToolSpec(d.id,"${d.serverName}: ${d.description}".take(500),McpToolPermissionPolicy.permission(d.readOnlyHint),false)){arguments->
   if(!repository.isToolGranted(d.id))return@register AgentToolResult(false,error="MCP 工具授权已撤销")
   val current=repository.findServer(d.serverId)?:return@register AgentToolResult(false,error="MCP 服务已删除")
   val raw=arguments["json"]?:"{}"
   McpToolArgumentValidator.validate(d.inputSchemaJson,raw)?.let{return@register AgentToolResult(false,error=it)}
   try{
    val output=withContext(Dispatchers.IO){McpStreamableHttpClient(current.endpoint,repository.readCredential(current.id)).callTool(d.name,JSONObject(raw),d.inputSchemaJson)}
    runCatching{repository.recordAudit(d.id,true)}
    AgentToolResult(true,output.take(32_000))
   } catch(cancelled:CancellationException){throw cancelled}
   catch(e:Exception){
    runCatching{repository.recordAudit(d.id,false)}
    AgentToolResult(false,error=(e.message?:"MCP 工具调用失败").take(2_000))
   }
  }
 }
}
