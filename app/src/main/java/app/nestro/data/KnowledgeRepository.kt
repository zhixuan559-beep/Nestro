package app.nestro.data
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Project-scoped knowledge base persistence and retrieval boundary. */
class KnowledgeRepository(private val database: NestroDatabase, private val context: Context? = null) {
    suspend fun documents(projectId: String): List<KnowledgeDocumentEntity> =
        withContext(Dispatchers.IO) { database.dao().knowledgeDocuments(projectId) }
    suspend fun importText(context: Context, resolver: ContentResolver, uri: Uri, projectId: String): KnowledgeImportResult =
        KnowledgeStore.importText(context, resolver, uri, projectId, database.dao())
    suspend fun search(projectId: String, query: String, limit: Int = 6): List<KnowledgeSearchResult> =
        KnowledgeStore.search(database.dao(), projectId, query, limit, context, ensureVectorIndex = true)
    suspend fun documentContent(documentId: String, projectId: String): String =
        withContext(Dispatchers.IO) {
            KnowledgeChunker.reassemble(database.dao().knowledgeChunksForDocument(documentId, projectId).map { it.content })
        }
    suspend fun rebuildVectorIndex(projectId: String) {
        val appContext = context ?: error("知识库仓库未绑定应用 Context。")
        KnowledgeStore.rebuildVectorIndex(appContext, database.dao(), projectId)
    }
    suspend fun deleteDocument(documentId: String, projectId: String) =
        withContext(Dispatchers.IO) { database.dao().deleteKnowledgeDocumentAndIndex(documentId, projectId) }
}
