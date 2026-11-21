package app.nestro.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTextPolicyTest {
    @Test
    fun trimsWhitespaceAndRemovesUnsupportedControlCharacters() {
        assertEquals("你好世界", SpeechTextPolicy.normalize("  你好\\u0000世界\\u0007  "))
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(SpeechTextPolicy.normalize("  \\n\\t "))
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
