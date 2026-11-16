package app.nestro.data

import org.junit.Assert.*
import org.junit.Test

class ExpertProfileCodecTest {
    @Test fun roundTripsExpertConfiguration() {
        val profile = ExpertProfile(group="研究", avatar="🦊", enabled=false, skillNames=listOf("检索","写作"), temperature=0.7, maxTokens=2048, description="研究型专家", version="2.1.0", source="本地")
        val decoded = ExpertProfileCodec.decode(ExpertProfileCodec.encode(mapOf("研究员" to profile))).getValue("研究员")
        assertEquals(profile, decoded)
    }

    @Test fun excludesDisabledOrMissingSkillsFromExecutionContext() {
        val profile = ExpertProfile(skillNames=listOf("检索","写作","不存在"))
        val context = ExpertProfileCodec.skillContext(profile, mapOf("检索" to "检索指令", "写作" to "写作指令"), setOf("写作"))
        assertTrue(context.contains("检索指令"))
        assertFalse(context.contains("写作指令"))
        assertFalse(context.contains("不存在"))
    }

    @Test fun validatesSkillReferencesAndModelParameters() {
        assertNotNull(ExpertProfileCodec.validate(ExpertProfile(skillNames=listOf("不存在")), emptySet()))
        assertNotNull(ExpertProfileCodec.validate(ExpertProfile(temperature=2.1), emptySet()))
        assertNotNull(ExpertProfileCodec.validate(ExpertProfile(maxTokens=0), emptySet()))
        assertNull(ExpertProfileCodec.validate(ExpertProfile(skillNames=listOf("检索"), temperature=0.8, maxTokens=2048), setOf("检索")))
    }
}
