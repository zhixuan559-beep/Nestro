package app.nestro.data

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

data class MemoryCandidate(
    val category: String,
    val content: String,
    val confidence: Double
)

data class MemoryRecall(
    val context: String,
    val memories: List<UserMemoryEntity>
)

data class MemoryMutationToken internal constructor(
    val projectVersion: Long,
    val globalVersion: Long
)

object UserMemoryStore {
    private val mutationVersions = ConcurrentHashMap<String, AtomicLong>()
    private val mutationLocks = ConcurrentHashMap<String, Mutex>()
    private val globalMutationVersion = AtomicLong(0L)

    private fun mutationLock(projectId: String): Mutex =
        mutationLocks.computeIfAbsent(projectId) { Mutex() }

    fun mutationVersion(projectId: String): MemoryMutationToken {
        require(projectId.isNotBlank()) { "记忆项目 ID 无效" }
        return MemoryMutationToken(
            projectVersion = mutationVersions.computeIfAbsent(projectId) { AtomicLong(0L) }.get(),
            globalVersion = globalMutationVersion.get()
        )
    }

    internal fun isCurrentMutationVersion(projectId: String, token: MemoryMutationToken): Boolean =
        mutationVersion(projectId) == token

    private fun bumpMutationVersion(projectId: String) {
        mutationVersions.computeIfAbsent(projectId) { AtomicLong(0L) }.incrementAndGet()
    }

    /** Invalidate in-flight extraction before deleting or replacing a project's memory/conversation data. */
    suspend fun invalidateAndAwaitPendingExtractions(projectId: String) {
        require(projectId.isNotBlank()) { "记忆项目 ID 无效" }
        bumpMutationVersion(projectId)
        mutationLock(projectId).withLock { }
    }

    /** Invalidate every in-flight extraction before a full backup restore or global memory shutdown. */
    suspend fun invalidateAndAwaitPendingExtractions() {
        globalMutationVersion.incrementAndGet()
        mutationVersions.values.forEach { it.incrementAndGet() }
        mutationLocks.values.toList().forEach { lock -> lock.withLock { } }
    }

    private val allowedCategories = setOf("preference", "workflow", "project", "constraint")

    fun isSafeContent(value: String): Boolean = !containsSensitiveData(value)

    suspend fun saveCandidates(
        dao: NestroDao,
        projectId: String,
        conversationId: String,
        messageId: String,
        candidates: List<MemoryCandidate>,
        expectedMutationVersion: MemoryMutationToken? = null
    ): Int {
        require(projectId.isNotBlank() && projectId.length <= 120) { "记忆项目 ID 无效" }
        require(conversationId.isNotBlank() && conversationId.length <= 120) { "记忆来源会话 ID 无效" }
        require(messageId.isNotBlank() && messageId.length <= 120) { "记忆来源消息 ID 无效" }
        val tokenAtStart = expectedMutationVersion ?: mutationVersion(projectId)
        return mutationLock(projectId).withLock {
            if (!isCurrentMutationVersion(projectId, tokenAtStart)) return@withLock 0
            var saved = 0
            candidates.take(5).forEach { candidate ->
                val category = candidate.category.trim().lowercase()
                val content = candidate.content.trim().replace(Regex("\\s+"), " ")
                if (category !in allowedCategories || content.length !in 8..180) return@forEach
                if (!candidate.confidence.isFinite() || candidate.confidence !in 0.8..1.0 || containsSensitiveData(content)) return@forEach
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
            if (saved > 0) {
                dao.trimUserMemories(projectId, 200)
                bumpMutationVersion(projectId)
            }
            saved
        }
    }

    suspend fun importOne(dao: NestroDao, projectId: String, memory: UserMemoryEntity): Boolean {
        require(projectId.isNotBlank() && memory.projectId == projectId) { "导入记忆项目范围不匹配" }
        return mutationLock(projectId).withLock {
            val imported = dao.importUserMemoryIfAbsent(memory)
            if (imported) bumpMutationVersion(projectId)
            imported
        }
    }

    suspend fun clearProject(dao: NestroDao, projectId: String) {
        require(projectId.isNotBlank()) { "记忆项目 ID 无效" }
        mutationLock(projectId).withLock {
            dao.deleteUserMemoriesForProject(projectId)
            bumpMutationVersion(projectId)
        }
    }

    suspend fun setShareAcrossProjects(
        dao: NestroDao,
        id: String,
        ownerProjectId: String,
        enabled: Boolean
    ): Boolean {
        require(id.isNotBlank() && ownerProjectId.isNotBlank()) { "记忆 ID 或所属项目无效" }
        return mutationLock(ownerProjectId).withLock {
            val updated = dao.setUserMemoryShareAcrossProjects(
                id, ownerProjectId, enabled, System.currentTimeMillis()
            )
            if (updated > 0) bumpMutationVersion(ownerProjectId)
            updated > 0
        }
    }

    suspend fun recall(dao: NestroDao, projectId: String, query: String, limit: Int = 6): MemoryRecall =
        recall(dao.userMemoriesForRecall(projectId, 200), query, limit)

    fun recall(memories: List<UserMemoryEntity>, query: String, limit: Int = 6): MemoryRecall {
        if (limit <= 0) return MemoryRecall("", emptyList())
        val terms = extractTerms(query).take(16)
        if (terms.isEmpty()) return MemoryRecall("", emptyList())
        val ranked = memories.map { memory ->
            val score = lexicalScore(memory, query, terms)
            memory to score
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<UserMemoryEntity, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .take(limit)
            .map { it.first }
        return buildRecall(ranked)
    }

    /**
     * Combines local BGE semantic similarity with lexical matches. Missing or malformed
     * vectors never suppress the keyword fallback; callers can safely use this only
     * when the optional on-device embedding model is installed.
     */
    fun recallSemantic(
        memories: List<UserMemoryEntity>,
        query: String,
        queryVector: FloatArray,
        memoryVectors: Map<String, FloatArray>,
        limit: Int = 6
    ): MemoryRecall {
        if (limit <= 0 || queryVector.isEmpty() || queryVector.any { !it.isFinite() }) {
            return recall(memories, query, limit)
        }
        val terms = extractTerms(query).take(16)
        val semanticRanked = memories.mapNotNull { memory ->
            val vector = memoryVectors[memory.id] ?: return@mapNotNull null
            if (vector.size != queryVector.size || vector.isEmpty() || vector.any { !it.isFinite() }) return@mapNotNull null
            val queryNorm = sqrt(queryVector.sumOf { it.toDouble() * it.toDouble() })
            val memoryNorm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
            if (queryNorm <= 0.0 || memoryNorm <= 0.0) return@mapNotNull null
            val similarity = queryVector.indices.sumOf { index ->
                queryVector[index].toDouble() * vector[index].toDouble()
            } / (queryNorm * memoryNorm)
            val lexical = lexicalScore(memory, query, terms)
            if (similarity < 0.32 && lexical == 0) return@mapNotNull null
            val lexicalScore = if (terms.isEmpty()) 0.0 else
                (lexical.toDouble() / (terms.size + 3.0)).coerceIn(0.0, 1.0)
            val combined = similarity.coerceIn(-1.0, 1.0) * 0.78 + lexicalScore * 0.22
            memory to combined
        }.sortedWith(
            compareByDescending<Pair<UserMemoryEntity, Double>> { it.second }
                .thenByDescending { it.first.updatedAt }
        ).map { it.first }

        val keywordFallback = recall(memories, query, limit).memories
        val merged = (semanticRanked + keywordFallback)
            .distinctBy { it.id }
            .take(limit)
        return buildRecall(merged)
    }

    private fun lexicalScore(memory: UserMemoryEntity, query: String, terms: List<String>): Int {
        val content = memory.content.lowercase()
        return terms.count { term -> content.contains(term) } +
            if (query.length >= 4 && content.contains(query.lowercase())) 3 else 0
    }

    private fun buildRecall(ranked: List<UserMemoryEntity>): MemoryRecall {
        val context = ranked.joinToString("\n") {
            "- [${it.category}] ${it.content} (memoryId=${it.id}, sourceProjectId=${it.projectId}, sourceConversationId=${it.sourceConversationId}, sourceMessageId=${it.sourceMessageId})"
        }.take(2600)
        return MemoryRecall(
            if (context.isBlank()) "" else "当前项目的自动记忆（仅作参考，事实以当前用户输入为准）：\n$context",
            ranked
        )
    }

    suspend fun list(dao: NestroDao, projectId: String): List<UserMemoryEntity> =
        dao.userMemories(projectId, 200)

    suspend fun edit(dao: NestroDao, id: String, projectId: String, content: String, category: String) {
        val normalized = content.trim().replace(Regex("\\s+"), " ")
        require(id.isNotBlank() && projectId.isNotBlank()) { "记忆 ID 或项目 ID 无效" }
        require(normalized.length in 8..180) { "记忆内容长度必须为 8 到 180 个字符" }
        require(category in allowedCategories) { "记忆分类无效" }
        require(!containsSensitiveData(normalized)) { "记忆包含敏感信息，请勿自动保存" }
        mutationLock(projectId).withLock {
            val nextFingerprint = fingerprint(category + "|" + normalized.lowercase())
            val duplicate = dao.userMemoryByFingerprint(projectId, nextFingerprint)
            require(duplicate == null || duplicate.id == id) { "已有相同记忆，请直接编辑现有条目" }
            val updated = dao.updateUserMemory(id, projectId, normalized, category, nextFingerprint, System.currentTimeMillis())
            check(updated == 1) { "记忆不存在或已被删除，未执行更新" }
            bumpMutationVersion(projectId)
        }
    }

    suspend fun delete(dao: NestroDao, id: String, projectId: String) {
        require(id.isNotBlank() && projectId.isNotBlank()) { "记忆 ID 或项目 ID 无效" }
        mutationLock(projectId).withLock {
            val deleted = dao.deleteUserMemory(id, projectId)
            check(deleted == 1) { "记忆不存在或已被删除" }
            bumpMutationVersion(projectId)
        }
    }

    private fun extractTerms(query: String): List<String> {
        val terms = linkedSetOf<String>()
        Regex("[a-z0-9_]{2,}", RegexOption.IGNORE_CASE).findAll(query).forEach { terms += it.value.lowercase() }
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(query).forEach { match ->
            val word = match.value
            for (i in 0 until (word.length - 1)) terms += word.substring(i, i + 2)
        }
        if (query.trim().length in 2..12) terms += query.trim().lowercase()
        return terms.toList()
    }

    private fun containsSensitiveData(value: String): Boolean =
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
            Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)").containsMatchIn(value) ||
            Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)").containsMatchIn(value) ||
            Regex("(?<!\\d)\\d{16,19}(?!\\d)").containsMatchIn(value) ||
            listOf(
                "密码", "验证码", "银行卡", "身份证", "病史", "疾病", "诊断", "症状", "用药",
                "宗教", "政治立场", "性取向", "住址", "家庭住址", "出生日期", "生日",
                "年龄", "收入", "负债", "欠款", "征信", "债务", "工资", "薪资"
            ).any { value.contains(it, true) }

    internal fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
