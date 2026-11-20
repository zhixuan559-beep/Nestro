package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeVectorSearchTest {
    @Test fun vectorEncodingRoundTripsAndRanksByCosineSimilarity() {
        val rows = listOf(
            KnowledgeChunkEmbeddingEntity("orthogonal", "model", 3, KnowledgeVectorSearch.encode(floatArrayOf(0f, 1f, 0f))),
            KnowledgeChunkEmbeddingEntity("best", "model", 3, KnowledgeVectorSearch.encode(floatArrayOf(1f, 0f, 0f))),
            KnowledgeChunkEmbeddingEntity("opposite", "model", 3, KnowledgeVectorSearch.encode(floatArrayOf(-1f, 0f, 0f)))
        )
        assertEquals(listOf("best", "orthogonal", "opposite"), KnowledgeVectorSearch.rank(floatArrayOf(1f, 0f, 0f), rows, 3).map { it.chunkId })
        assertEquals(floatArrayOf(1f, 0f, 0f).toList(), KnowledgeVectorSearch.decode(rows[1].embedding, 3)?.toList())
    }

    @Test fun malformedOrWrongDimensionVectorsAreIgnored() {
        assertNull(KnowledgeVectorSearch.decode(byteArrayOf(1, 2, 3), 3))
        val rows = listOf(
            KnowledgeChunkEmbeddingEntity("bad", "model", 2, byteArrayOf(1, 2, 3)),
            KnowledgeChunkEmbeddingEntity("wrong", "model", 2, KnowledgeVectorSearch.encode(floatArrayOf(1f, 0f)))
        )
        val result = KnowledgeVectorSearch.rank(floatArrayOf(1f, 0f, 0f), rows, 5)
        assertEquals(listOf("wrong"), result.map { it.chunkId })
    }
}
