package app.nestro.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

data class KnowledgeImportResult(val document: KnowledgeDocumentEntity, val duplicate: Boolean)

object KnowledgeStore {
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 500_000
    private const val CHUNK_SIZE = 1_000
    private const val CHUNK_OVERLAP = 120
    private const val MAX_CHUNKS = 500

    suspend fun importText(resolver: ContentResolver, uri: Uri, projectId: String, dao: NestroDao): KnowledgeImportResult =
        withContext(Dispatchers.IO) {
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "导入文档"
            val extension = name.substringAfterLast('.', "").lowercase()
            require(extension in setOf("txt", "md", "markdown", "html", "htm", "csv", "json", "xml", "log") ||
                mime.startsWith("text/") || mime == "application/json") {
                "当前版本支持 TXT、Markdown、HTML、CSV、JSON、XML 等文本文件；PDF/Office 解析正在开发。"
            }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_FILE_BYTES) { "文件超过 2 MB，请先拆分或压缩。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("无法读取所选文件")
            val raw = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            val parsed = if (extension in setOf("html", "htm") || mime.contains("html")) {
                Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()
            } else raw
            val normalized = parsed.replace("\u0000", "").replace("\r\n", "\n").trim()
            require(normalized.isNotBlank()) { "文件没有可索引的文本内容。" }
            require(normalized.length <= MAX_TEXT_CHARS) { "文本超过 50 万字符，请拆分文件后导入。" }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest((projectId + "\u0000" + normalized).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val existing = dao.knowledgeDocumentByHash(hash)
            if (existing != null) return@withContext KnowledgeImportResult(existing, true)
            val document = KnowledgeDocumentEntity(
                UUID.randomUUID().toString(), projectId, name.take(180),
                mime.ifBlank { "text/plain" }, bytes.size.toLong(), System.currentTimeMillis(), hash
            )
            val chunks = split(normalized).take(MAX_CHUNKS).mapIndexed { index, part ->
                KnowledgeChunkEntity(UUID.randomUUID().toString(), document.id, projectId, index, part)
            }
            require(chunks.isNotEmpty()) { "无法生成有效文档片段。" }
            dao.insertKnowledgeBundle(document, chunks)
            KnowledgeImportResult(document, false)
        }

    suspend fun retrieve(dao: NestroDao, projectId: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val terms = prompt.split(Regex("[\\s，。！？；：、,.!?;:]+"))
                .map { it.trim().take(36) }.filter { it.length >= 2 }.distinct().take(6)
            if (terms.isEmpty()) return@withContext ""
            val found = linkedMapOf<String, KnowledgeChunkEntity>()
            terms.forEach { term -> dao.searchKnowledgeChunks(projectId, term, 5).forEach { found[it.id] = it } }
            if (found.isEmpty()) return@withContext ""
            val documents = dao.knowledgeDocuments(projectId).associateBy { it.id }
            val snippets = found.values.take(6).joinToString("\n\n") { chunk ->
                val source = documents[chunk.documentId]?.name ?: "本地文档"
                "[来源：$source · 片段 ${chunk.position + 1}]\n${chunk.content.take(1200)}"
            }.take(7000)
            "以下内容来自当前项目空间的本地知识库，仅在相关时参考，并在回答中标注来源：\n$snippets"
        }

    private fun split(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length && result.size < MAX_CHUNKS) {
            var end = minOf(start + CHUNK_SIZE, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', '!', '?'), end)
                if (boundary > start + CHUNK_SIZE / 2) end = boundary + 1
            }
            val part = text.substring(start, end).trim()
            if (part.isNotBlank()) result += part
            if (end >= text.length) break
            start = maxOf(start + 1, end - CHUNK_OVERLAP)
        }
        result
    }
}
