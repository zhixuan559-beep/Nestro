package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpertBundleParserTest {
    @Test fun expertBundleRoundTripsWithRolePrompt() {
        val parsed=ExpertBundleParser.parse(ExpertBundleParser.exportJson("商业分析师","先核对数据，再给出判断。","负责商业研究"),"analyst.nestro-expert.json")
        assertEquals("商业分析师",parsed.name)
        assertEquals("1.0.0",parsed.version)
        assertEquals("负责商业研究",parsed.description)
        assertEquals("先核对数据，再给出判断。",parsed.prompt)
    }
    @Test fun rejectsEmptyOrOversizedPrompt() {
        assertThrows(IllegalArgumentException::class.java) { ExpertBundleParser.exportJson("专家"," ") }
        assertThrows(IllegalArgumentException::class.java) { ExpertBundleParser.exportJson("专家","指令".repeat(4_001)) }
    }
    @Test fun rejectsInvalidVersionAndMissingName() {
        assertThrows(IllegalArgumentException::class.java) { ExpertBundleParser.parse("""{"name":"专家","version":"latest","prompt":"分析任务"}""","expert.json") }
        assertThrows(IllegalArgumentException::class.java) { ExpertBundleParser.parse("""{"name":"","version":"1.0.0","prompt":"分析任务"}""","expert.json") }
    }
    @Test fun roundTripsExtendedExpertProfile() {
        val profile = ExpertProfile(group = "研究", avatar = "🦊", enabled = false, skillNames = listOf("检索", "写作"), temperature = 0.6, maxTokens = 4096, description = "深度研究", version = "2.0.1", source = "团队共享")
        val parsed = ExpertBundleParser.parse(ExpertBundleParser.exportJson("研究员", "先检索，再核验。", "研究专家", profile), "researcher.json")
        assertEquals("研究", parsed.group)
        assertEquals("🦊", parsed.avatar)
        assertEquals(false, parsed.enabled)
        assertEquals(listOf("检索", "写作"), parsed.skillNames)
        assertEquals(0.6, parsed.temperature!!, 0.0001)
        assertEquals(4096, parsed.maxTokens)
        assertEquals("团队共享", parsed.source)
    }

    @Test fun rejectsInvalidExpertProfileParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            ExpertBundleParser.parse("""{"name":"研究员","prompt":"分析任务","temperature":2.1}""", "expert.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpertBundleParser.parse("""{"name":"研究员","prompt":"分析任务","skills":[""]}""", "expert.json")
        }
    }

}
