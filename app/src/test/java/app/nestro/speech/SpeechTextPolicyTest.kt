package app.nestro.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTextPolicyTest {
    @Test
    fun trimsWhitespaceAndRemovesUnsupportedControlCharacters() {
        val input = "  你好" + 0.toChar() + "世界" + 7.toChar() + "  "
        assertEquals("你好世界", SpeechTextPolicy.normalize(input))
    }

    @Test
    fun rejectsBlankInput() {
        val input = "  " + 10.toChar() + 9.toChar() + " "
        assertNull(SpeechTextPolicy.normalize(input))
    }

    @Test
    fun acceptsExactlyTheMaximumLength() {
        assertEquals(
            SpeechTextPolicy.MAX_CHARACTERS,
            SpeechTextPolicy.normalize("a".repeat(SpeechTextPolicy.MAX_CHARACTERS))?.length
        )
    }

    @Test
    fun rejectsTextLongerThanTheMaximumLength() {
        assertNull(SpeechTextPolicy.normalize("a".repeat(SpeechTextPolicy.MAX_CHARACTERS + 1)))
    }
}
