package app.nestro.data

import org.junit.Assert.*
import org.junit.Test

class ExpertProfileCodecTest {
    @Test fun roundTripsExpertConfiguration() {
        val profile = ExpertProfile(group="研究", avatar="🦊", enabled=false, skillNames=listOf("检索","写作"), temperature=0.7, maxTokens=2048, description="研究型专家", version="2.1.0", source="本地")
        val decoded = ExpertProfileCodec.decode(ExpertProfileCodec.encode(mapOf("研究员" to profile))).getValue("研究员")
        assertEquals(profile, decoded)
    }

    @Test fun excludesSkillsWithMissingOrDisabledTransitiveDependencies() {
        val profile = ExpertProfile(skillNames = listOf("根技能", "独立技能"))
        val skills = mapOf("根技能" to "root", "中间技能" to "middle", "独立技能" to "standalone")
        val metadata = mapOf(
            "根技能" to """{"version":"1.0.0","dependencies":[{"name":"中间技能","version":"^1.0.0"}]}""",
            "中间技能" to """{"version":"1.0.0","dependencies":[{"name":"缺失技能","version":"*"}]}"""
        )
        val context = ExpertProfileCodec.skillContext(profile, skills, emptySet(), metadata)
        assertFalse(context.contains("root"))
        assertTrue(context.contains("standalone"))
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

    @Test fun roundTripsMascotSelectionAndRejectsUnknownMascot() {
        // 改版第 b 步：吉祥物形象随专家配置一起存取；旧数据缺省为卡皮巴拉。
        val profile = ExpertProfile(mascot = "")
        val decoded = ExpertProfileCodec.decode(ExpertProfileCodec.encode(mapOf("助手" to profile))).getValue("助手")
        assertEquals(profile, decoded)
        assertEquals("capybara", ExpertProfileCodec.decode("{\"旧专家\":{}}").getValue("旧专家").mascot)
        assertNotNull(ExpertProfileCodec.validate(ExpertProfile(mascot = "团子"), emptySet()))
        assertNull(ExpertProfileCodec.validate(ExpertProfile(mascot = "capybara"), emptySet()))
        assertNull(ExpertProfileCodec.validate(ExpertProfile(mascot = ""), emptySet()))
    }
}
