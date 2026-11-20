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


    @Test
    fun exportedSkillPreservesPermissionsAndDependencies() {
        val metadata = """{"version":"1.2.0","description":"研究","source":"local","permissions":["network_read"],"dependencies":[{"name":"web-search","version":">=1.0"}]}"""
        val exported = org.json.JSONObject(SkillBundleParser.exportJson("资料研究员", prompt, metadata))
        assertEquals("资料研究员", exported.getString("name"))
        assertEquals("1.2.0", exported.getString("version"))
        assertEquals("network_read", exported.getJSONArray("permissions").getString(0))
        assertEquals("web-search", exported.getJSONArray("dependencies").getJSONObject(0).getString("name"))
        assertEquals(prompt, exported.getString("prompt"))
    }

    @Test
    fun exportsLocalSkillWithoutMetadataUsingSafeDefaults() {
        val exported = org.json.JSONObject(SkillBundleParser.exportJson("自定义技能", prompt, null))
        assertEquals("1.0.0", exported.getString("version"))
        assertTrue(exported.getJSONArray("permissions").length() == 0)
        assertTrue(exported.getJSONArray("dependencies").length() == 0)
    }

    @Test
    fun rejectsMalformedDependencyConstraintsAndDuplicateDependencies() {
        val invalid = """{"name":"坏技能","version":"1.0.0","prompt":"执行","dependencies":[{"name":"搜索","version":"latest"}]}"""
        try {
            SkillBundleParser.parse(invalid, "bad.json")
            fail("unknown version tags must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        val duplicate = """{"name":"重复依赖","version":"1.0.0","prompt":"执行","dependencies":[{"name":"搜索","version":"*"},{"name":"搜索","version":"^1.0.0"}]}"""
        try {
            SkillBundleParser.parse(duplicate, "duplicate.json")
            fail("duplicate dependency names must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun shortLocalSkillCanRoundTripThroughExportAndImport() {
        val exported = SkillBundleParser.exportJson("短技能", "只输出摘要。", null)
        val imported = SkillBundleParser.parse(exported, "short.nestro-skill.json")
        assertEquals("短技能", imported.name)
        assertEquals("只输出摘要。", imported.prompt)
    }
}
