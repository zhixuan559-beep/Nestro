package app.nestro.data

import org.junit.Assert.*
import org.junit.Test

class ExpertRevisionAndTemplateTest {
    @Test fun versionsIncrementAndDiffIsUseful() {
        assertEquals("1.2.4", ExpertRevisionCodec.bumpVersion("1.2.3"))
        assertEquals("1.0.1", ExpertRevisionCodec.bumpVersion("bad"))
        val diff = ExpertRevisionCodec.diff("第一行\n旧内容", "第一行\n新内容")
        assertTrue(diff.any { it == "- 旧内容" })
        assertTrue(diff.any { it == "+ 新内容" })
    }

    @Test fun revisionsAndTemplatesRoundTrip() {
        val profile = ExpertProfile(group="研究", avatar="🦊", skillNames=listOf("检索"))
        val revisions = mapOf("研究员" to listOf(ExpertRevision("1.0.0", "旧提示词", profile, 123L)))
        val restored = ExpertRevisionCodec.decode(ExpertRevisionCodec.encode(revisions))
        assertEquals("旧提示词", restored.getValue("研究员").single().prompt)
        assertEquals(profile, restored.getValue("研究员").single().profile)
        val templates = listOf(ExpertTemplate(name="研究模板", description="说明", prompt="提示词", profile=profile))
        val restoredTemplate = ExpertTemplateCodec.decode(ExpertTemplateCodec.encode(templates)).single()
        assertEquals("研究模板", restoredTemplate.name)
        assertEquals(profile, restoredTemplate.profile)
    }
}
