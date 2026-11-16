package app.nestro.data

/**
 * Splits imported text into overlapping retrieval chunks.
 * Fails rather than silently dropping the tail when the safety cap is reached.
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
            }

            val part = text.substring(start, end).trim()
            if (part.isNotBlank()) result += part
            if (end >= text.length) {
                start = text.length
                break
            }
            start = maxOf(start + 1, end - overlap)
        }

        require(start >= text.length) {
            "文档切分超过 $maxChunks 个片段，请缩短文档或减少过密的段落换行后重新导入。"
        }
        return result
    }
}
