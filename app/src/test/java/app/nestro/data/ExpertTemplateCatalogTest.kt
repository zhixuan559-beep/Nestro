package app.nestro.data

import org.junit.Assert.*
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
            ExpertTemplate(name = "重复", description = "", prompt = "一", profile = ExpertProfile()),
            ExpertTemplate(name = "重复", description = "", prompt = "二", profile = ExpertProfile())
        )
        try {
            ExpertTemplateBundleCodec.encode(duplicate)
            fail("duplicate names must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun templateBundleRejectsOversizedPrompt() {
        val invalid = ExpertTemplate(name = "超长", description = "", prompt = "x".repeat(8001), profile = ExpertProfile())
        try {
            ExpertTemplateBundleCodec.encode(listOf(invalid))
            fail("oversized prompt must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
