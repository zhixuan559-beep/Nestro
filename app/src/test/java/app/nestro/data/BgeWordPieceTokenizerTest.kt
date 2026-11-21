package app.nestro.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BgeWordPieceTokenizerTest {
    private val tokenizer = BgeWordPieceTokenizer(
        listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "我", "爱", "北", "京", "hello", "world", "##s", "!")
    )

    @Test fun chineseEnglishAndWordPieceTokensAreEncodedWithSpecialTokens() {
        assertArrayEquals(longArrayOf(2, 4, 5, 6, 7, 8, 9, 10, 11, 3), tokenizer.encode("我爱北京 HELLO worlds!"))
    }

    @Test fun emptyTextStillProducesValidSpecialTokenSequence() {
        assertArrayEquals(longArrayOf(2, 3), tokenizer.encode(""))
    }

    @Test fun encodingHonorsMaximumSequenceLength() {
        val short = BgeWordPieceTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "我"), maxLength = 3)
        assertEquals(3, short.encode("我我我").size)
    }
}
