package app.nestro.data

/**
 * Splits imported text into overlapping retrieval chunks without trimming or
 * dropping source characters. Chunk boundaries never split UTF-16 surrogate pairs.
 */
object KnowledgeChunker {
    fun split(
        text: String,
        chunkSize: Int = 1_000,
        overlap: Int = 120,
        maxChunks: Int = 600
    ): List<String> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(overlap >= 0 && overlap < chunkSize) { "overlap must be smaller than chunkSize" }
        require(maxChunks > 0) { "maxChunks must be positive" }
        if (text.isEmpty()) return emptyList()

        val result = ArrayList<String>()
        var start = 0
        while (start < text.length && result.size < maxChunks) {
            var end = minOf(start + chunkSize, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOfAny(
                    charArrayOf('\n', '。', '！', '？', '.', '!', '?'),
                    end - 1
                )
                if (boundary > start + chunkSize / 2) end = boundary + 1
                if (end < text.length && end > 0 &&
                    text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
                ) end--
            }

            result += text.substring(start, end)
            if (end >= text.length) {
                start = text.length
                break
            }

            var nextStart = maxOf(start + 1, end - overlap)
            if (nextStart > 0 && nextStart < text.length &&
                text[nextStart].isLowSurrogate() && text[nextStart - 1].isHighSurrogate()
            ) nextStart++
            start = nextStart
        }

        require(start >= text.length) {
            "文档切分超过 $maxChunks 个片段，请缩短文档或减少过密的段落换行后重新导入。"
        }
        return result
    }

    /** Reassembles overlapping chunks for a full-document preview. */
    fun reassemble(chunks: List<String>, maxOverlap: Int = 120): String {
        require(maxOverlap >= 0) { "maxOverlap must not be negative" }
        if (chunks.isEmpty()) return ""
        val output = StringBuilder(chunks.first())
        for (chunk in chunks.drop(1)) {
            val upperBound = minOf(maxOverlap, output.length, chunk.length)
            var overlap = upperBound
            while (overlap > 0 &&
                output.substring(output.length - overlap) != chunk.substring(0, overlap)
            ) overlap--
            output.append(chunk.substring(overlap))
        }
        return output.toString()
    }
}
