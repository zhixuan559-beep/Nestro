package app.nestro.data

import java.security.MessageDigest
import java.util.UUID

data class MemoryCandidate(
    val category: String,
    val content: String,
    val confidence: Double
)

data class MemoryRecall(
    val context: String,
    val memories: List<UserMemoryEntity>
)

object UserMemoryStore {
    private val allowedCategories = setOf("preference", "workflow", "project", "constraint")

    suspend fun saveCandidates(
        dao: NestroDao,
        projectId: String,
        conversationId: String,
        messageId: String,
        candidates: List<MemoryCandidate>
    ): Int {
        var saved = 0
        candidates.take(5).forEach { candidate ->
            val category = candidate.category.trim().lowercase()
            val content = candidate.content.trim().replace(Regex("\\s+"), " ")
            if (category !in allowedCategories || content.length !in 8..180) return@forEach
            if (candidate.confidence !in 0.8..1.0 || containsSensitiveData(content)) return@forEach
            val fingerprint = fingerprint(category + "|" + content.lowercase())
            val old = dao.userMemoryByFingerprint(projectId, fingerprint)
            val now = System.currentTimeMillis()
            dao.putUserMemory(
                UserMemoryEntity(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    projectId = projectId,
                    category = category,
                    content = content,
                    fingerprint = fingerprint,
                    sourceConversationId = conversationId,
                    sourceMessageId = messageId,
                    confidence = candidate.confidence,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now
                )
            )
            saved++
        }
        return saved
    }

    suspend fun recall(dao: NestroDao, projectId: String, query: String, limit: Int = 6): MemoryRecall {
        val memories = dao.userMemories(projectId, 200)
        val terms = extractTerms(query).take(16)
        if (terms.isEmpty()) return MemoryRecall("", emptyList())
        val ranked = memories.map { memory ->
            val content = memory.content.lowercase()
            val score = terms.count { term -> content.contains(term) } +
                if (query.length >= 4 && content.contains(query.lowercase())) 3 else 0
            memory to score
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<UserMemoryEntity, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .take(limit)
            .map { it.first }
        val context = ranked.joinToString("\\n") {
            "- [${it.category}] ${it.content} (memoryId=${it.id}, sourceConversationId=${it.sourceConversationId}, sourceMessageId=${it.sourceMessageId})"
        }.take(2600)
        return MemoryRecall(
            if (context.isBlank()) "" else "当前项目的自动记忆（仅作参考，事实以当前用户输入为准）：\\n" + context,
            ranked
        )
    }

    suspend fun list(dao: NestroDao, projectId: String): List<UserMemoryEntity> =
        dao.userMemories(projectId, 200)

    suspend fun edit(dao: NestroDao, id: String, projectId: String, content: String, category: String) {
        val normalized = content.trim().replace(Regex("\\s+"), " ")
        require(normalized.length in 8..180) { "记忆内容长度必须为 8 到 180 个字符" }
        require(category in allowedCategories) { "记忆分类无效" }
        require(!containsSensitiveData(normalized)) { "记忆包含敏感信息，请勿自动保存" }
        dao.updateUserMemory(id, projectId, normalized, category, System.currentTimeMillis())
    }

    suspend fun delete(dao: NestroDao, id: String, projectId: String) {
        dao.deleteUserMemory(id, projectId)
    }

    private fun extractTerms(query: String): List<String> {
        val terms = linkedSetOf<String>()
        Regex("[a-z0-9_]{2,}", RegexOption.IGNORE_CASE).findAll(query).forEach { terms += it.value.lowercase() }
        val cjk = Regex("[\\u4e00-\\u9fff]+").findAll(query).joinToString("") { it.value }
        for (i in 0 until (cjk.length - 1).coerceAtLeast(0)) terms += cjk.substring(i, i + 2)
        if (query.trim().length in 2..12) terms += query.trim().lowercase()
        return terms.toList()
    }

    private fun containsSensitiveData(value: String): Boolean =
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
            Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)").containsMatchIn(value) ||
            Regex("(?<!\\d)\\d{16,19}(?!\\d)").containsMatchIn(value) ||
            listOf("密码", "验证码", "银行卡", "身份证", "病史", "疾病", "诊断", "宗教", "政治立场", "性取向").any { value.contains(it, true) }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
