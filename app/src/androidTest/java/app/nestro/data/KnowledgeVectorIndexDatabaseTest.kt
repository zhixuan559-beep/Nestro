package app.nestro.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeVectorIndexDatabaseTest {
    @Test fun embeddingsRemainProjectScopedAndCascadeWhenDocumentIsDeleted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            val now = System.currentTimeMillis()
            val docA = KnowledgeDocumentEntity("doc-a", "project-a", "A.txt", "text/plain", 1, now, "hash-a")
            val docB = KnowledgeDocumentEntity("doc-b", "project-b", "B.txt", "text/plain", 1, now, "hash-b")
            val chunkA = KnowledgeChunkEntity("chunk-a", "doc-a", "project-a", 0, "项目 A 的知识片段")
            val chunkB = KnowledgeChunkEntity("chunk-b", "doc-b", "project-b", 0, "项目 B 的知识片段")
            dao.insertKnowledgeBundleIfAbsent(docA, listOf(chunkA))
            dao.insertKnowledgeBundleIfAbsent(docB, listOf(chunkB))
            dao.putKnowledgeChunkEmbeddings(
                listOf(
                    KnowledgeChunkEmbeddingEntity("chunk-a", KnowledgeEmbeddingModelManager.MODEL_ID, 2, KnowledgeVectorSearch.encode(floatArrayOf(1f, 0f))),
                    KnowledgeChunkEmbeddingEntity("chunk-b", KnowledgeEmbeddingModelManager.MODEL_ID, 2, KnowledgeVectorSearch.encode(floatArrayOf(0f, 1f)))
                )
            )
            assertEquals(listOf("chunk-a"), dao.knowledgeChunkEmbeddingsForProject("project-a", KnowledgeEmbeddingModelManager.MODEL_ID).map { it.chunkId })
            assertEquals(listOf("chunk-b"), dao.knowledgeChunkEmbeddingsForProject("project-b", KnowledgeEmbeddingModelManager.MODEL_ID).map { it.chunkId })
            dao.deleteKnowledgeDocumentAndIndex("doc-a", "project-a")
            assertEquals(0, dao.knowledgeChunkEmbeddingCount("project-a", KnowledgeEmbeddingModelManager.MODEL_ID))
            assertEquals(listOf("chunk-b"), dao.knowledgeChunkEmbeddingsForProject("project-b", KnowledgeEmbeddingModelManager.MODEL_ID).map { it.chunkId })
        } finally {
            database.close()
        }
    }
}
