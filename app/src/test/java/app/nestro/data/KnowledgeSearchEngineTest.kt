package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSearchEngineTest {
    private fun chunk(id: String, position: Int, content: String) =
        KnowledgeChunkEntity(id, "doc", "project", position, content)

    @Test
    fun extractsChineseAndLatinTerms() {
        val terms = KnowledgeSearchEngine.extractTerms("骑手片区风险预警 Android GPS")
        assertTrue(terms.contains("骑手"))
        assertTrue(terms.contains("片区"))
        assertTrue(terms.contains("android"))
        assertTrue(terms.contains("gps"))
    }

    @Test
    fun ignoresGenericPromptWords() {
        val terms = KnowledgeSearchEngine.extractTerms("请帮我看一下这个文档里面的内容")
        assertTrue(terms.isEmpty())
    }

    @Test
    fun ranksCoverageAboveRepeatedSingleKeyword() {
        val repeated = chunk("repeat", 0, "风险风险风险风险风险风险")
        val broad = chunk("broad", 1, "风险预警片区骑手")
        val ranked = KnowledgeSearchEngine.rank("骑手片区风险预警", listOf(repeated, broad))
        assertEquals("broad", ranked.first().chunk.id)
    }

    @Test
    fun returnsMatchedTermsForSourceExplanation() {
        val ranked = KnowledgeSearchEngine.rank(
            "Android GPS 定位",
            listOf(chunk("a", 0, "Android 应用使用 GPS 定位"))
        )
        assertTrue(ranked.single().matchedTerms.contains("android"))
        assertTrue(ranked.single().matchedTerms.contains("gps"))
    }

    @Test
    fun emptyQueryAndNonPositiveLimitReturnNoResults() {
        assertTrue(KnowledgeSearchEngine.rank("请帮我看一下", listOf(chunk("a", 0, "内容"))).isEmpty())
        assertTrue(KnowledgeSearchEngine.rank("Android", listOf(chunk("a", 0, "Android")), 0).isEmpty())
        assertTrue(KnowledgeSearchEngine.extractTerms("Android", 0).isEmpty())
    }

    @Test
    fun usesProjectWideCorpusStatisticsForBm25() {
        val common = chunk("common", 0, "Android")
        val rare = chunk("rare", 1, "Android GPS 定位")
        val ranked = KnowledgeSearchEngine.rank("Android GPS", listOf(common, rare), corpusSize = 1_000,
            documentFrequencies = mapOf("android" to 900, "gps" to 2), averageDocumentLength = 20.0)
        assertEquals("rare", ranked.first().chunk.id)
    }
    @Test
    fun extractsTermsFromLongChineseQueriesWithoutDroppingTheTail() {
        val prompt = "骑手片区风险预警" + "夜间道路安全".repeat(40) + "定位异常处理"
        val terms = KnowledgeSearchEngine.extractTerms(prompt)
        assertTrue("长查询末尾的关键词也应参与召回", terms.any { it == "异常" || it == "处理" || it == "异常处" })
    }
    @Test
    fun rewardsRareQueryTermsOverRepeatedCommonTerms() {
        val focused = chunk("focused", 0, "Android GPS 定位导航")
        val noisy = chunk("noisy", 1, "Android Android Android Android Android")
        assertEquals("focused", KnowledgeSearchEngine.rank("Android GPS", listOf(noisy, focused)).first().chunk.id)
    }
    @Test
    fun normalizesForChunkLength() {
        val concise = chunk("concise", 0, "Android GPS")
        val verbose = chunk("verbose", 1, "Android GPS " + "irrelevant ".repeat(300))
        assertEquals("concise", KnowledgeSearchEngine.rank("Android GPS", listOf(verbose, concise)).first().chunk.id)
    }
    @Test
    fun orderingIsDeterministicForEqualScores() {
        val ranked = KnowledgeSearchEngine.rank(
            "Android",
            listOf(chunk("later", 8, "Android"), chunk("earlier", 2, "Android"))
        )
        assertEquals(listOf("earlier", "later"), ranked.map { it.chunk.id })
    }
}
