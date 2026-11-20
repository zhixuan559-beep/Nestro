package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class KnowledgeEmbeddingModelInstrumentedTest {
    @Test fun downloadsAndRunsChineseEmbeddingModelLocally() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        KnowledgeEmbeddingModelManager.install(context)
        assertTrue(KnowledgeEmbeddingModelManager.isInstalled(context))

        val vectors = KnowledgeEmbeddingModelManager.embed(
            context,
            listOf(
                "如何提高中文文档检索的准确率",
                "怎样让中文资料搜索结果更加精准",
                "今天足球比赛的比分和天气预报"
            )
        )
        assertEquals(3, vectors.size)
        assertTrue(vectors.all { it.size == 512 && it.all { value -> value.isFinite() } })
        val relatedSimilarity = cosine(vectors[0], vectors[1])
        val unrelatedSimilarity = cosine(vectors[0], vectors[2])
        assertTrue(
            "中文语义近义问题应比无关主题更相似：related=$relatedSimilarity unrelated=$unrelatedSimilarity",
            relatedSimilarity > unrelatedSimilarity
        )
    }

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        val dot = left.indices.sumOf { left[it].toDouble() * right[it].toDouble() }
        val leftNorm = sqrt(left.sumOf { it.toDouble() * it.toDouble() })
        val rightNorm = sqrt(right.sumOf { it.toDouble() * it.toDouble() })
        return dot / (leftNorm * rightNorm)
    }
}
