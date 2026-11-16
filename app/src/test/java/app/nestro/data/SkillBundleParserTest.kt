package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SkillBundleParserTest {
    private val prompt = "请先理解用户目标，再核对事实来源，区分事实与推断，输出可执行步骤，并在涉及敏感操作时交还用户确认。对于无法访问或无法核验的信息必须明确说明，不得猜测价格、库存、时间或完成状态，并记录关键依据。"

    @Test
    fun parsesSkillBundleAndPermissionDeclarations() {
        val raw = """
            {
              "name": "资料研究员",
              "version": "1.2.0",
              "description": "检索与交叉核验",
              "prompt": "$prompt",
              "permissions": ["network_read"],
              "dependencies": [{"name": "web-search", "version": ">=1.0"}]
            }
        """.trimIndent()
        val bundle = SkillBundleParser.parse(raw, "research.skill.json")
        assertEquals("资料研究员", bundle.name)
        assertEquals("1.2.0", bundle.version)
        assertEquals(listOf("network_read"), bundle.permissions)
        assertEquals("web-search", bundle.dependencies.single().name)
    }

    @Test
    fun rejectsUnknownPermissionInsteadOfSilentlyGrantingIt() {
        val raw = """
            {"name":"风险技能","version":"1.0.0","prompt":"$prompt","permissions":["root_access"]}
        """.trimIndent()
        try {
            SkillBundleParser.parse(raw, "risk.json")
            fail("unknown permission must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun markdownImportUsesFilenameAndNoPermissions() {
        val bundle = SkillBundleParser.parse(prompt, "写作助手.md")
        assertEquals("写作助手", bundle.name)
        assertTrue(bundle.permissions.isEmpty())
        assertEquals("1.0.0", bundle.version)
    }

    @Test
    fun metadataRoundTripsAsJson() {
        val bundle = SkillBundleParser.parse(
            """{"name":"研究员","version":"2.0.1","prompt":"$prompt","permissions":["network_read"]}""",
            "research.json"
        )
        val metadata = org.json.JSONObject(SkillBundleParser.metadataJson(bundle))
        assertEquals("2.0.1", metadata.getString("version"))
        assertEquals("network_read", metadata.getJSONArray("permissions").getString(0))
    }
}
