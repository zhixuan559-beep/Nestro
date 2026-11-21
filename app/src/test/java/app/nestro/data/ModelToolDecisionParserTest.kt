package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelToolDecisionParserTest {
    @Test
    fun parsesDirectReplyFromJsonFence() {
        val result = ModelToolDecisionParser.parse(
            """```json
{"action":"reply","text":"这件事可以直接回答。"}
```"""
        )
        assertEquals("reply", result?.action)
        assertEquals("这件事可以直接回答。", result?.text)
    }

    @Test
    fun parsesToolArgumentsWithoutLosingJsonTypes() {
        val result = ModelToolDecisionParser.parse(
            """{"action":"tool","tool_id":"knowledge.search","arguments":{"query":"合同条款","limit":4,"includeArchived":false}}"""
        )
        assertEquals("tool", result?.action)
        assertEquals("knowledge.search", result?.toolId)
        assertEquals("合同条款", result?.arguments?.get("query"))
        assertEquals("4", result?.arguments?.get("limit"))
        assertEquals("false", result?.arguments?.get("includeArchived"))
    }

    @Test
    fun parsesStreamableDirectAndToolPrefixes() {
        val direct = ModelToolDecisionParser.parse("DIRECT\\n答案正文")
        assertEquals("reply", direct?.action)
        assertEquals("答案正文", direct?.text)

        val tool = ModelToolDecisionParser.parse(
            "TOOL\\n" + """{"tool_id":"knowledge.search","arguments":{"query":"项目文档"}}"""
        )
        assertEquals("tool", tool?.action)
        assertEquals("knowledge.search", tool?.toolId)
        assertEquals("项目文档", tool?.arguments?.get("query"))
    }

    @Test
    fun rejectsUnknownActionsAndMalformedJson() {
        assertNull(ModelToolDecisionParser.parse("""{"action":"shell","command":"id"}"""))
        assertNull(ModelToolDecisionParser.parse("""{"action":"tool","tool_id":"""))
        assertTrue(ModelToolDecisionParser.parse("ordinary model answer") == null)
    }
}
