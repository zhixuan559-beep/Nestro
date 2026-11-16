package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSseDecoderTest {
    @Test
    fun decodesStandardDeltaEvent() {
        val decoder = OpenAiSseDecoder()
        assertNull(decoder.acceptLine("""data: {"choices":[{"delta":{"content":"你好"}}]}"""))
        assertEquals("你好", decoder.acceptLine(""))
        assertFalse(decoder.isDone)
    }

    @Test
    fun joinsMultipleDataLinesBeforeParsingJson() {
        val decoder = OpenAiSseDecoder()
        decoder.acceptLine("""data: {"choices":[""")
        decoder.acceptLine("""data: {"delta":{"content":"分片"}}]}""")
        assertEquals("分片", decoder.acceptLine(""))
    }

    @Test
    fun doneMarkerStopsFurtherEvents() {
        val decoder = OpenAiSseDecoder()
        decoder.acceptLine("data: [DONE]")
        assertNull(decoder.acceptLine(""))
        assertTrue(decoder.isDone)
        assertNull(decoder.acceptLine("""data: {"choices":[{"delta":{"content":"late"}}]}"""))
    }

    @Test
    fun flushesFinalEventWhenProviderOmitsTrailingBlankLine() {
        val decoder = OpenAiSseDecoder()
        decoder.acceptLine("""data: {"choices":[{"delta":{"content":"末尾"}}]}""")
        assertEquals("末尾", decoder.finish())
    }

    @Test
    fun ignoresCommentsAndSupportsTextParts() {
        val decoder = OpenAiSseDecoder()
        assertNull(decoder.acceptLine(": keep-alive"))
        decoder.acceptLine("""data: {"choices":[{"delta":{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}]}""")
        assertEquals("AB", decoder.acceptLine(""))
    }
}
