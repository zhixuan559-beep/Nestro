package app.nestro.data

import kotlin.math.ln

/**
 * Deterministic, on-device lexical retrieval.
 *
 * Candidate selection remains project-scoped in Room. Ranking uses a BM25-style
 * term-frequency and document-length normalization over the candidate set; it
 * has no network or model dependency.
 */
object KnowledgeSearchEngine {
    data class Match(
        val chunk: KnowledgeChunkEntity,
        val score: Int,
        val matchedTerms: List<String>
    )

    private val ignoredTerms = setOf(
        "请帮我", "帮我", "请帮", "一下", "这个", "那个", "进行", "关于", "如何",
        "可以", "里面", "内容", "文档", "怎么", "什么", "然后", "现在", "之前",
        "我们", "你们", "他们", "一个", "没有", "不是", "需要"
    )

    fun extractTerms(prompt: String, limit: Int = 32): List<String> {
        if (limit <= 0) return emptyList()
        val latinTerms = linkedSetOf<String>()
        val normalized = prompt.lowercase()
        Regex("[a-z0-9][a-z0-9_-]{1,31}").findAll(normalized).forEach {
            val term = it.value
            if (term !in ignoredTerms) latinTerms += term
        }

        val cjkTerms = linkedSetOf<String>()
        val cjkSearchText = ignoredTerms
            .sortedByDescending { it.length }
            .fold(normalized) { text, ignored -> text.replace(ignored, " ") }
        val cjkRuns = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]+")
            .findAll(cjkSearchText)
            .map { it.value }
        cjkRuns.forEach { run ->
            for (size in listOf(3, 2)) {
                if (run.length < size) continue
                for (index in 0..(run.length - size)) {
                    val term = run.substring(index, index + size)
                    if (term !in ignoredTerms) cjkTerms += term
                }
            }
        }

        if (latinTerms.size >= limit) return latinTerms.take(limit)
        val selected = latinTerms.toMutableList()
        val remaining = limit - selected.size
        if (cjkTerms.size <= remaining) return selected + cjkTerms
        if (remaining == 1) {
            selected += cjkTerms.first()
        } else if (remaining > 1) {
            val candidates = cjkTerms.toList()
            for (slot in 0 until remaining) {
                val index = slot * (candidates.size - 1) / (remaining - 1)
                selected += candidates[index]
            }
        }
        return selected.distinct().take(limit)
    }

    fun rank(
        prompt: String,
        chunks: List<KnowledgeChunkEntity>,
        limit: Int = 6,
        corpusSize: Int = chunks.size,
        documentFrequencies: Map<String, Int> = emptyMap(),
        averageDocumentLength: Double? = null
    ): List<Match> {
        if (limit <= 0 || chunks.isEmpty()) return emptyList()
        val terms = extractTerms(prompt)
        if (terms.isEmpty()) return emptyList()

        val tokenized = chunks.map { chunk ->
            val content = chunk.content.lowercase()
            val frequencies = terms.associateWith { term ->
                Regex(Regex.escape(term)).findAll(content).count()
            }
            Triple(chunk, frequencies, content.length.coerceAtLeast(1))
        }
        val averageLength = (averageDocumentLength ?: tokenized.map { it.third }.average()).coerceAtLeast(1.0)
        val documentFrequency = terms.associateWith { term ->
            documentFrequencies[term]
                ?.coerceIn(0, corpusSize.coerceAtLeast(0))
                ?: tokenized.count { (_, frequencies, _) -> (frequencies[term] ?: 0) > 0 }
        }
        val totalDocuments = corpusSize.coerceAtLeast(tokenized.size).toDouble()

        return tokenized.mapNotNull { (chunk, frequencies, length) ->
            val matched = terms.filter { (frequencies[it] ?: 0) > 0 }
            if (matched.isEmpty()) return@mapNotNull null

            val normalizedLength = length / averageLength
            val score = matched.sumOf { term ->
                val tf = (frequencies[term] ?: 0).toDouble()
                val df = (documentFrequency[term] ?: 0).toDouble()
                val idf = ln(1.0 + (totalDocuments - df + 0.5) / (df + 0.5))
                val k1 = 1.2
                val b = 0.75
                idf * (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * normalizedLength))
            }
            // A small coverage bonus rewards chunks that address more query terms.
            val coverageBonus = matched.size.toDouble() / terms.size.coerceAtLeast(1)
            Match(chunk, ((score + coverageBonus) * 10_000).toInt(), matched)
        }.sortedWith(
            compareByDescending<Match> { it.score }
                .thenBy { it.chunk.position }
                .thenBy { it.chunk.id }
        ).take(limit)
    }
}
