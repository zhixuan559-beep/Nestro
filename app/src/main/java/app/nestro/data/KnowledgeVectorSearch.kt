package app.nestro.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal object KnowledgeVectorSearch {
    data class Match(val chunkId: String, val similarity: Double)

    fun encode(vector: FloatArray): ByteArray {
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "向量必须包含有限数值。" }
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray, dimensions: Int): FloatArray? {
        if (dimensions <= 0 || bytes.size != dimensions * Float.SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(dimensions).also(buffer::get).takeIf { vector -> vector.all { it.isFinite() } }
    }

    fun rank(
        query: FloatArray,
        rows: List<KnowledgeChunkEmbeddingEntity>,
        limit: Int
    ): List<Match> {
        if (limit <= 0 || query.isEmpty() || query.any { !it.isFinite() }) return emptyList()
        val queryNorm = sqrt(query.sumOf { it.toDouble() * it.toDouble() })
        if (queryNorm <= 0.0) return emptyList()
        return rows.mapNotNull { row ->
            if (row.dimensions != query.size) return@mapNotNull null
            val vector = decode(row.embedding, row.dimensions) ?: return@mapNotNull null
            val vectorNorm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
            if (vectorNorm <= 0.0) return@mapNotNull null
            val dot = query.indices.sumOf { index -> query[index].toDouble() * vector[index].toDouble() }
            Match(row.chunkId, (dot / (queryNorm * vectorNorm)).coerceIn(-1.0, 1.0))
        }.sortedWith(compareByDescending<Match> { it.similarity }.thenBy { it.chunkId }).take(limit)
    }
}
