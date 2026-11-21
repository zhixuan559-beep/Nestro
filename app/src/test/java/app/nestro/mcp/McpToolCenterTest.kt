package app.nestro.mcp
import app.nestro.agent.AgentToolPermission
import org.junit.Assert.*
import org.junit.Test
class McpToolCenterTest {
 @Test fun endpointRequiresHttpsAndRejectsEmbeddedCredentials(){
  assertNull(McpEndpointPolicy.validate("https://example.com/mcp"))
  assertNotNull(McpEndpointPolicy.validate("http://example.com/mcp"))
  assertNotNull(McpEndpointPolicy.validate("https://user:pass@example.com/mcp"))
  assertNotNull(McpEndpointPolicy.validate("https://example.com/mcp?token=x"))
  assertNotNull(McpEndpointPolicy.validate("https://example.com/mcp#fragment"))
 }
 @Test fun permissionDefaultsToExternalWriteUnlessReadOnlyIsExplicit(){
  assertEquals(AgentToolPermission.READ_ONLY,McpToolPermissionPolicy.permission(true))
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
 @Test fun serverAndToolCodecsRoundTripAndKeepGrants(){
  val server=McpServerRecord("srv-1","Docs","https://example.com/mcp")
  assertEquals(listOf(server),McpToolCenterCodec.decodeServers(McpToolCenterCodec.encodeServers(listOf(server))))
  val tool=McpToolDescriptor(McpToolCenterCodec.toolId("srv-1","search"),"srv-1","Docs","search","Search docs","""{"type":"object","properties":{}}""",true,true)
  assertEquals(tool,McpToolCenterCodec.decodeTools(McpToolCenterCodec.encodeTools(listOf(tool))).single())
  assertEquals(tool.id,McpToolCenterCodec.toolId("srv-1","search"))
 }
}
