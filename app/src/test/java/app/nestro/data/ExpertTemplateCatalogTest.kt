package app.nestro.data

import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test

class ExpertTemplateCatalogTest {
    @Test fun builtInTemplatesHaveUniqueNamesAndValidContent() {
        val templates = ExpertTemplateCatalog.builtIns()
        assertTrue(templates.size >= 8)
        assertEquals(templates.size, templates.map { it.name }.distinct().size)
        assertTrue(templates.all { it.builtIn && it.prompt.isNotBlank() && it.category.isNotBlank() })
    }

    @Test fun templateBundleRoundTripsCategoryAndProfile() {
        val template = ExpertTemplate(
            name = "研究模板",
            description = "研究用",
            prompt = "先核验事实，再分析。",
            profile = ExpertProfile(group = "研究", avatar = "🦊", skillNames = listOf("检索")),
            category = "研究分析"
        )
        val restored = ExpertTemplateBundleCodec.decode(ExpertTemplateBundleCodec.encode(listOf(template))).single()
        assertEquals(template.name, restored.name)
        assertEquals(template.prompt, restored.prompt)
        assertEquals(template.category, restored.category)
        assertEquals(template.profile.group, restored.profile.group)
        assertFalse(restored.builtIn)
    }

    @Test fun templateBundleRejectsUnknownFormatAndDuplicateNames() {
        try {
            ExpertTemplateBundleCodec.decode("""{"format":"other","version":1,"templates":[]}""")
            fail("unknown format must be rejected")
        } catch (_: IllegalArgumentException) { }

        val duplicate = listOf(
            ExpertTemplate(name = "Template", description = "", prompt = "一", profile = ExpertProfile()),
            ExpertTemplate(name = "template", description = "", prompt = "二", profile = ExpertProfile())
        )
        try {
            ExpertTemplateBundleCodec.encode(duplicate)
            fail("duplicate names must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun templateBundleRejectsDuplicateIdsEvenWhenNamesDiffer() {
        val duplicateId = "same-template-id"
        val templates = listOf(
            ExpertTemplate(id = duplicateId, name = "研究模板", description = "", prompt = "研究", profile = ExpertProfile()),
            ExpertTemplate(id = duplicateId, name = "写作模板", description = "", prompt = "写作", profile = ExpertProfile())
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExpertTemplateBundleCodec.encode(templates)
        }
    }

    @Test fun localTemplateDecodeRepairsDuplicateIdsWithoutDroppingLegacyEntries() {
        val raw = """[
            {"id":"legacy-id","name":"Alpha","prompt":"first"},
            {"id":"legacy-id","name":"alpha","prompt":"second"}
        ]"""
        val decoded = ExpertTemplateCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertNotEquals(decoded[0].id, decoded[1].id)
        assertEquals(listOf("Alpha", "alpha"), decoded.map { it.name })
    }

    @Test fun templateBundleDecodeRejectsDuplicateIds() {
        val root = JSONObject(
            ExpertTemplateBundleCodec.encode(
                listOf(
                    ExpertTemplate(name = "研究模板", description = "", prompt = "研究", profile = ExpertProfile()),
                    ExpertTemplate(name = "写作模板", description = "", prompt = "写作", profile = ExpertProfile())
                )
            )
        )
        val rows = root.getJSONArray("templates")
        rows.getJSONObject(1).put("id", rows.getJSONObject(0).getString("id"))
        assertThrows(IllegalArgumentException::class.java) {
            ExpertTemplateBundleCodec.decode(root.toString())
        }
    }

    @Test fun templateBundleDecodeRejectsCaseInsensitiveDuplicateNames() {
        val root = JSONObject(
            ExpertTemplateBundleCodec.encode(
                listOf(
                    ExpertTemplate(name = "Alpha", description = "", prompt = "A", profile = ExpertProfile()),
                    ExpertTemplate(name = "Beta", description = "", prompt = "B", profile = ExpertProfile())
                )
            )
        )
        val rows = root.getJSONArray("templates")
        rows.getJSONObject(1).put("name", "alpha")
        assertThrows(IllegalArgumentException::class.java) {
            ExpertTemplateBundleCodec.decode(root.toString())
        }
    }

    @Test fun templateBundleRejectsBlankTemplateIds() {
        val invalid = ExpertTemplate(id = " ", name = "无效 ID", description = "", prompt = "提示", profile = ExpertProfile())
        assertThrows(IllegalArgumentException::class.java) {
            ExpertTemplateBundleCodec.encode(listOf(invalid))
        }
    }

    @Test fun localTemplateCatalogRejectsOversizedAggregatePayload() {
        val templates = (1..50).map { index ->
            ExpertTemplate(
                name = "模板$index",
                description = "",
                prompt = "x".repeat(7_000),
                profile = ExpertProfile()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpertTemplateCodec.encode(templates)
        }
    }

    @Test fun templateBundleRejectsOversizedPrompt() {
        val invalid = ExpertTemplate(name = "超长", description = "", prompt = "x".repeat(8001), profile = ExpertProfile())
        try {
            ExpertTemplateBundleCodec.encode(listOf(invalid))
            fail("oversized prompt must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
