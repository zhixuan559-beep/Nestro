package app.nestro.mcp
import app.nestro.agent.AgentToolPermission
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class McpToolCenterTest {
 @Test fun endpointRequiresHttpsAndRejectsEmbeddedCredentials(){
  assertNull(McpEndpointPolicy.validate("https://example.com/mcp"))
  assertNotNull(McpEndpointPolicy.validate("http://example.com/mcp"))
  assertNotNull(McpEndpointPolicy.validate("https://user:pass@example.com/mcp"))
  assertNotNull(McpEndpointPolicy.validate("https://example.com/mcp?token=x"))
  assertNotNull(McpEndpointPolicy.validate("https://example.com/mcp#fragment"))
 }
 @Test fun permissionDefaultsToExternalWriteUnlessReadOnlyIsExplicit(){
  assertEquals(AgentToolPermission.EXTERNAL_WRITE,McpToolPermissionPolicy.permission(true))
  assertEquals(AgentToolPermission.EXTERNAL_WRITE,McpToolPermissionPolicy.permission(false))
 }
 @Test fun argumentValidatorChecksRequiredTypesAndUnknownProperties(){
  val schema="""{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"],"additionalProperties":false}"""
  assertNull(McpToolArgumentValidator.validate(schema,"""{"query":"hello","limit":3}"""))
  assertEquals("缺少必填参数：query",McpToolArgumentValidator.validate(schema,"""{"limit":3}"""))
  assertEquals("参数 limit 类型不匹配，要求 integer",McpToolArgumentValidator.validate(schema,"""{"query":"hello","limit":"3"}"""))
  assertEquals("不允许未声明的参数：extra",McpToolArgumentValidator.validate(schema,"""{"query":"hello","extra":1}"""))
  assertNotNull(McpToolArgumentValidator.validate(schema,"x".repeat(32_001)))
 }
 @Test fun argumentMapUsesSchemaTypesRatherThanGuessingStringValues(){
  val schema="""{"type":"object","properties":{"text":{"type":"string"},"flag":{"type":"boolean"},"count":{"type":"integer"}}}"""
  val encoded=JSONObject(McpToolArgumentValidator.encodeArgumentMap(schema,mapOf("text" to "true","flag" to "false","count" to "3")))
  assertEquals("true",encoded.getString("text"))
  assertFalse(encoded.getBoolean("flag"))
  assertEquals(3,encoded.getInt("count"))
 }
 @Test fun malformedPersistedServerConfigurationCanBeQuarantined(){
  assertNull(McpToolCenterCodec.decodeServersOrNull("""[{"id":"bad","name":"bad","endpoint":"http://unsafe"}]"""))
 }
 @Test fun modernHeaderValuesEncodeUnsafeCharactersAndSentinel(){
  assertEquals("plain-ascii",McpHeaderValueEncoder.encode("plain-ascii"))
  assertTrue(McpHeaderValueEncoder.encode("hello 世界").startsWith("=?base64?"))
  assertTrue(McpHeaderValueEncoder.encode("=?base64?literal?=").startsWith("=?base64?"))
 }
 @Test fun serverAndToolCodecsRoundTripAndKeepGrants(){
  val server=McpServerRecord("srv-1","Docs","https://example.com/mcp")
  assertEquals(listOf(server),McpToolCenterCodec.decodeServers(McpToolCenterCodec.encodeServers(listOf(server))))
  val tool=McpToolDescriptor(McpToolCenterCodec.toolId("srv-1","search"),"srv-1","Docs","search","Search docs","""{"type":"object","properties":{}}""",true,true)
  assertEquals(tool,McpToolCenterCodec.decodeTools(McpToolCenterCodec.encodeTools(listOf(tool))).single())
  assertEquals(tool.id,McpToolCenterCodec.toolId("srv-1","search"))
 }

 @Test fun auditCodecRoundTripsBoundedHistory(){
  val rows=listOf(McpToolAuditRecord("a","Docs","search",true,123L))
  assertEquals(rows,McpAuditCodec.decodeOrNull(McpAuditCodec.encode(rows)))
  assertNull(McpAuditCodec.decodeOrNull("not-json"))
  val oversized = "[" + List(101) { """{"id":"$it","serverName":"Docs","toolName":"search","succeeded":true,"createdAt":123}""" }.joinToString(",") + "]"
  assertNull(McpAuditCodec.decodeOrNull(oversized))
 }
 @Test fun auditCodecRejectsMissingRequiredFields(){
  assertNull(McpAuditCodec.decodeOrNull("""[{"id":"a","serverName":"Docs","toolName":"search","createdAt":123}]"""))
 }
 @Test fun damagedAuditIsPreservedBeforeRecoveryAndNotOverwritten(){
  val damaged="""[{"id":"broken","serverName":"Docs"}]"""
  var preserved:String?=null
  assertTrue(McpAuditRecovery.decodeOrPreserve(damaged,false){preserved=it}.isEmpty())
  assertEquals(damaged,preserved)
  preserved="existing-backup"
  assertTrue(McpAuditRecovery.decodeOrPreserve(damaged,true){preserved=it}.also{assertTrue(it.isEmpty())}.isEmpty())
  assertEquals("existing-backup",preserved)
 }
 @Test fun validAuditDoesNotTriggerQuarantine(){
  var quarantined=false
  val rows=listOf(McpToolAuditRecord("a","Docs","search",true,123L))
  assertEquals(rows,McpAuditRecovery.decodeOrPreserve(McpAuditCodec.encode(rows),false){quarantined=true})
  assertFalse(quarantined)
 }
 @Test fun failedAuditQuarantinePersistenceIsNotSilentlyIgnored(){
  var threw=false
  try {
   McpAuditRecovery.decodeOrPreserve("not-json",false){error("disk write failed")}
  } catch(e:IllegalStateException) { threw=e.message=="disk write failed" }
  assertTrue(threw)
 }

}
