package app.nestro.data

/**
 * Deterministic lexical retrieval for the on-device project knowledge base.
 * It intentionally has no network or model dependency.
 */
object KnowledgeSearchEngine {
    data class Match(
        val chunk: KnowledgeChunkEntity,
        val score: Int,
        val matchedTerms: List<String>
    )

    private val ignoredTerms = setOf(
        "帮我", "请帮", "一下", "这个", "那个", "进行", "关于", "如何",
        "可以", "里面", "内容", "文档", "怎么", "什么", "然后", "现在", "之前",
        "我们", "你们", "他们", "一个", "没有", "不是", "需要"
    )

    fun extractTerms(prompt: String, limit: Int = 24): List<String> {
        if (limit <= 0) return emptyList()
        val terms = linkedSetOf<String>()
        val normalized = prompt.lowercase()
        Regex("[a-z0-9][a-z0-9_-]{1,31}").findAll(normalized).forEach {
            val term = it.value
            if (term !in ignoredTerms) terms += term
        }

        val cjkRuns = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]+")
            .findAll(normalized)
            .map { it.value }
        cjkRuns.forEach { run ->
            // Longer n-grams are inserted first, then overlapping bigrams preserve recall.
            for (size in listOf(3, 2)) {
                if (run.length < size) continue
                for (index in 0..(run.length - size)) {
                    val term = run.substring(index, index + size)
                    if (term !in ignoredTerms) terms += term
                }
            }
        }
        return terms.take(limit)
    }

    fun rank(
        prompt: String,
        chunks: List<KnowledgeChunkEntity>,
        limit: Int = 6
    ): List<Match> {
        if (limit <= 0 || chunks.isEmpty()) return emptyList()
        val terms = extractTerms(prompt)
        if (terms.isEmpty()) return emptyList()

        return chunks.mapNotNull { chunk ->
            val content = chunk.content.lowercase()
            val matched = terms.filter { content.contains(it) }
            if (matched.isEmpty()) return@mapNotNull null

            val frequency = matched.sumOf { term ->
                Regex(Regex.escape(term)).findAll(content).count().coerceAtMost(5)
            }
            val longerTermBonus = matched.sumOf { term -> if (term.length >= 3) 2 else 0 }
            // Coverage dominates frequency so a chunk answering several parts wins
            // over one that repeats a single keyword many times.
            val score = matched.size * 100 + frequency * 3 + longerTermBonus
            Match(chunk, score, matched)
        }.sortedWith(
            compareByDescending<Match> { it.score }
                .thenBy { it.chunk.position }
                .thenBy { it.chunk.id }
        ).take(limit)
    }
}
