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
}
