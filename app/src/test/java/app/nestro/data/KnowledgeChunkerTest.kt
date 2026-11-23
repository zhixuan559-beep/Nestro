package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeChunkerTest {
    @Test
    fun createsOverlappingChunksWithoutDroppingTail() {
        val source = "x".repeat(2_500)
        val chunks = KnowledgeChunker.split(source)
        assertEquals(3, chunks.size)
        assertEquals(1_000, chunks[0].length)
        assertEquals(1_000, chunks[1].length)
        assertEquals(740, chunks[2].length)
        assertTrue(chunks[0].takeLast(120) == chunks[1].take(120))
        assertTrue(chunks[1].takeLast(120) == chunks[2].take(120))
    }

    @Test
    fun punctuationBoundariesPreferReadableChunks() {
        val source = ("段落内容。").repeat(500)
        val chunks = KnowledgeChunker.split(source)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 1_000 })
    }

    @Test
    fun punctuationAtNextChunkBoundaryDoesNotOverflowChunkSize() {
        val source = "x".repeat(1_000) + "。" + "y".repeat(500)
        val chunks = KnowledgeChunker.split(source)
        assertTrue(chunks.all { it.length <= 1_000 })
    }

    @Test
    fun reassemblesOverlappingChunksWithoutDuplicatingText() {
        val source = "知识库内容。".repeat(700)
        assertEquals(source, KnowledgeChunker.reassemble(KnowledgeChunker.split(source)))
    }
    @Test
    fun neverSplitsUtf16SurrogatePairs() {
        val source = "a".repeat(999) + "😀" + "b".repeat(1_500)
        val chunks = KnowledgeChunker.split(source)
        assertEquals(source, KnowledgeChunker.reassemble(chunks))
        assertTrue(chunks.all { chunk -> chunk.zipWithNext().none { (left, right) -> left.isHighSurrogate() && !right.isLowSurrogate() } })
    }
    @Test
    fun refusesToSilentlyTruncateWhenChunkLimitIsReached() {
        val source = "x".repeat(5_000)
        val failure = runCatching {
            KnowledgeChunker.split(source, chunkSize = 100, overlap = 10, maxChunks = 2)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("超过 2 个片段") == true)
    }

    @Test
    fun rejectsInvalidChunkConfiguration() {
        assertTrue(runCatching { KnowledgeChunker.split("abc", chunkSize = 10, overlap = 10) }.isFailure)
    }

    @Test fun chunkLengthNeverExceedsLimitAtSurrogateBoundary() {
        val source = "a".repeat(9) + "😀" + "b".repeat(20)
        val chunks = KnowledgeChunker.split(source, chunkSize = 10, overlap = 2)
        assertTrue(chunks.all { it.length <= 10 })
        assertFalse(chunks.any { it.lastOrNull()?.isHighSurrogate() == true })
        assertFalse(chunks.any { it.firstOrNull()?.isLowSurrogate() == true })
        assertEquals(source, KnowledgeChunker.reassemble(chunks, maxOverlap = 2))
    }
}
