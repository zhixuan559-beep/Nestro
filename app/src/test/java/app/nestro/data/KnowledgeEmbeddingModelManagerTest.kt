package app.nestro.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeEmbeddingModelManagerTest {
    @Test
    fun meanPoolingUsesOnlyTokensIncludedByAttentionMask() {
        val pooled = meanPoolMaskedTokens(
            tokenVectors = listOf(
                floatArrayOf(100f, 100f),
                floatArrayOf(2f, 4f),
                floatArrayOf(6f, 8f),
                floatArrayOf(200f, 200f)
            ),
            attentionMask = longArrayOf(0, 1, 1, 0)
        )
        assertArrayEquals(floatArrayOf(4f, 6f), pooled, 0.0001f)
    }

    @Test
    fun meanPoolingRejectsMismatchedOrEmptyMasks() {
        assertThrows(IllegalArgumentException::class.java) {
            meanPoolMaskedTokens(listOf(floatArrayOf(1f)), longArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            meanPoolMaskedTokens(listOf(floatArrayOf(1f)), longArrayOf(0))
        }
    }

    @Test
    fun meanPoolingRejectsInvalidTokenDimensionsAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            meanPoolMaskedTokens(
                listOf(floatArrayOf(1f, 2f), floatArrayOf(3f)),
                longArrayOf(1, 1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            meanPoolMaskedTokens(listOf(floatArrayOf(Float.NaN)), longArrayOf(1))
        }
    }
}
