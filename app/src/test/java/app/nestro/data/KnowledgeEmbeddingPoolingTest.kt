package app.nestro.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeEmbeddingPoolingTest {
    @Test
    fun meanPoolingIgnoresPaddingTokens() {
        val pooled = meanPoolMaskedTokens(
            tokenVectors = listOf(
                floatArrayOf(1f, 3f),
                floatArrayOf(3f, 5f),
                floatArrayOf(100f, 100f)
            ),
            attentionMask = longArrayOf(1L, 1L, 0L)
        )
        assertArrayEquals(floatArrayOf(2f, 4f), pooled, 0.0001f)
    }

    @Test
    fun rejectsEmptyAttentionMask() {
        assertThrows(IllegalArgumentException::class.java) {
            meanPoolMaskedTokens(
                tokenVectors = listOf(floatArrayOf(1f, 2f)),
                attentionMask = longArrayOf(0L)
            )
        }
    }

    @Test
    fun rejectsMismatchedTokenDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            meanPoolMaskedTokens(
                tokenVectors = listOf(floatArrayOf(1f), floatArrayOf(1f, 2f)),
                attentionMask = longArrayOf(1L, 1L)
            )
        }
    }
}
