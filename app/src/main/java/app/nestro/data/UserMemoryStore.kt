package app.nestro.data

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt
import kotlin.math.ln

data class MemoryCandidate(
    val category: String,
    val content: String,
    val confidence: Double,
    val supersedesId: String? = null
)

data class MemoryRecall(
    val context: String,
    val memories: List<UserMemoryEntity>
)

data class MemoryMutationToken internal constructor(
    val projectVersion: Long,
    val globalVersion: Long
)

/** Offline memory recall using BM25-style weighting, CJK n-grams, phrase coverage and recency. */
internal object MemoryRecallRanker {
    private const val MAX_TERMS = 32
    private val stopWords = setOf("这个","那个","怎么","如何","什么","为什么","可以","一下","帮我","请问","之前","现在","然后","就是","一个","我们","你们","他们","还有","因为","所以","如果","的话","时候","进行","一下子","记住","希望","以后")

    fun rank(query: String, memories: List<UserMemoryEntity>, limit: Int = 6, now: Long = System.currentTimeMillis()): List<UserMemoryEntity> {
        if (limit <= 0 || memories.isEmpty()) return emptyList()
        val terms = extractTerms(query)
        if (terms.isEmpty()) return emptyList()
        val documents = memories.map { it.content.lowercase() }
        val frequencies = terms.associateWith { term -> documents.count { it.contains(term) } }
        val averageLength = documents.map { tokenizeLength(it) }.average().coerceAtLeast(1.0)
        val phrase = normalize(query)
        val minimumMatches = if (terms.size >= 4) 2 else 1
        val scored = memories.mapIndexed { index, memory ->
            val content = documents[index]
            val length = tokenizeLength(content).toDouble().coerceAtLeast(1.0)
            var score = 0.0
            var matched = 0
            terms.forEach { term ->
                val tf = countOccurrences(content, term).coerceAtMost(5)
                if (tf > 0) {
                    matched++
                    val df = frequencies[term] ?: 0
                    val idf = ln(1.0 + (memories.size - df + 0.5) / (df + 0.5))
                    score += idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * length / averageLength))
                }
            }
            if (matched == 0) return@mapIndexed memory to 0.0
            score += 2.4 * matched / terms.size
            if (phrase.length >= 3 && normalize(content).contains(phrase)) score += 4.0
            score += memory.confidence.coerceIn(0.0, 1.0) * 0.45
            val ageDays = (now - memory.updatedAt).coerceAtLeast(0L) / 86_400_000.0
            score += 0.35 / (1.0 + ageDays / 120.0)
            memory to score
        }.filter { (memory, score) -> score > 0.0 && terms.count { memory.content.lowercase().contains(it) } >= minimumMatches }
            .sortedWith(compareByDescending<Pair<UserMemoryEntity, Double>> { it.second }.thenByDescending { it.first.updatedAt })
        val selected = mutableListOf<UserMemoryEntity>()
        val seen = mutableSetOf<String>()
        for ((memory, _) in scored) {
            val key = normalize(memory.content)
            if (key.isNotBlank() && seen.add(key)) selected += memory
            if (selected.size >= limit) break
        }
        return selected
    }

    internal fun extractTerms(text: String): List<String> {
        val normalized = text.lowercase()
        val terms = linkedSetOf<String>()
        Regex("[a-z0-9_]{2,}|[\\u3400-\\u9fff\\uf900-\\ufaff]{2,}", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { match ->
                val run = match.value
                val first = run.first()
                if (first in 'a'..'z' || first.isDigit() || first == '_') {
                    terms += run
                } else {
                    if (run.length <= 12 && run !in stopWords) terms += run
                    for (size in listOf(3, 2)) {
                        var index = 0
                        while (index + size <= run.length) {
                            val gram = run.substring(index, index + size)
                            if (gram !in stopWords) terms += gram
                            index += if (size == 3) 2 else 1
                        }
                    }
                }
            }
        val filtered = terms.filter { it !in stopWords && it.length >= 2 }
        return if (filtered.size <= MAX_TERMS) filtered else filtered.takeLast(MAX_TERMS)
    }

    private fun tokenizeLength(value: String): Int =
        Regex("[a-z0-9_]{2,}|[\\u3400-\\u9fff\\uf900-\\ufaff]{1,}", RegexOption.IGNORE_CASE).findAll(value).count().coerceAtLeast(1)

    private fun countOccurrences(text: String, term: String): Int {
        var count = 0
        var start = 0
        while (start <= text.length - term.length) {
            val found = text.indexOf(term, start)
            if (found < 0) break
            count++
            start = found + term.length
        }
        return count
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\p{Punct}\\s，。！？、；：（）【】《》“”‘’]+"), "")
}

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
                val now = System.currentTimeMillis()
                val supersedesId = candidate.supersedesId?.trim().orEmpty()
                if (supersedesId.isNotBlank()) {
                    val replaced = dao.replaceUserMemoryIfUnconflicted(
                        id = supersedesId,
                        projectId = projectId,
                        category = category,
                        content = content,
                        fingerprint = fingerprint,
                        conversationId = conversationId,
                        messageId = messageId,
                        confidence = candidate.confidence,
                        updatedAt = now
                    )
                    // A failed explicit correction must not silently become a new memory.
                    if (replaced) saved++
                    return@forEach
                }
                dao.upsertUserMemoryByFingerprint(
                    UserMemoryEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        category = category,
                        content = content,
                        fingerprint = fingerprint,
                        sourceConversationId = conversationId,
                        sourceMessageId = messageId,
                        confidence = candidate.confidence,
                        createdAt = now,
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

    suspend fun saveManual(
        dao: NestroDao,
        projectId: String,
        content: String,
        category: String = "preference"
    ): Boolean {
        require(projectId.isNotBlank() && projectId.length <= 120 && dao.projectExists(projectId)) {
            "项目空间不存在或已被删除"
        }
        val normalized = content.trim().replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ")
        val normalizedCategory = category.trim().lowercase()
        require(normalized.length in 8..180) { "记忆内容长度必须为 8 到 180 个字符" }
        require(normalizedCategory in allowedCategories) { "记忆分类无效" }
        require(isSafeContent(normalized)) { "记忆包含敏感信息，请勿保存" }
        return mutationLock(projectId).withLock {
            val now = System.currentTimeMillis()
            val fingerprint = fingerprint(normalizedCategory + "|" + normalized.lowercase())
            val inserted = dao.insertUserMemoryIfAbsent(
                UserMemoryEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    category = normalizedCategory,
                    content = normalized,
                    fingerprint = fingerprint,
                    sourceConversationId = "",
                    sourceMessageId = "",
                    confidence = 1.0,
                    createdAt = now,
                    updatedAt = now
                )
            )
            if (inserted == -1L) return@withLock false
            bumpMutationVersion(projectId)
            dao.trimUserMemories(projectId, 200)
            true
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

    suspend fun recall(
        dao: NestroDao,
        projectId: String,
        query: String,
        limit: Int = 6,
        readableSourceProjectIds: Set<String> = emptySet()
    ): MemoryRecall = recall(
        dao.userMemoriesForRecall(projectId, readableSourceProjectIds.sorted(), 200),
        query,
        limit
    )

    fun recall(memories: List<UserMemoryEntity>, query: String, limit: Int = 6): MemoryRecall =
        buildRecall(MemoryRecallRanker.rank(query, memories, limit))

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
            if (context.isBlank()) "" else "当前项目及已获读取授权的来源项目中、由用户明确共享的自动记忆（仅作参考，事实以当前用户输入为准）：\n$context",
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
            Regex("""(?i)\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{20,}|npm_[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{30,}|AKIA[0-9A-Z]{16}|ya29\.[A-Za-z0-9_-]{20,}|mfa\.[A-Za-z0-9_-]{40,}|xox[baprs]-[A-Za-z0-9-]{10,})\b""").containsMatchIn(value) ||
            Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}""").containsMatchIn(value) ||
            Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""").containsMatchIn(value) ||
            Regex("""(?i)\b(?:api[\s_-]?key|access[\s_-]?token|refresh[\s_-]?token|private[\s_-]?key|password|passcode|secret|medical history|medical condition|health condition|diagnosis|allergy|medication|prescription|symptom|illness|disability|religion|political affiliation|sexual orientation|ethnicity|race|union membership|trade union|criminal history|sex life|sexual activity|social security number|national insurance number|tax id|home address|date of birth|salary|income|debt|bank account|passport|national id)\b""").containsMatchIn(value) ||
            Regex("""(?:我住在|家庭住址|详细地址|收货地址|住址是).{0,48}(?:省|市|区|县|路|街|巷|号|栋|单元|室)""").containsMatchIn(value) ||
            listOf(
                "密码", "口令", "密钥", "令牌", "验证码", "银行卡", "身份证", "护照",
                "病史", "疾病", "病情", "健康状况", "诊断", "症状", "过敏", "用药", "用药记录", "药物史",
                "宗教", "政治立场", "性取向", "性行为", "性生活", "种族", "民族", "工会",
                "犯罪记录", "犯罪史", "住址", "家庭住址", "详细地址", "收货地址", "门牌号",
                "出生日期", "生日", "年龄", "收入", "负债", "欠款", "征信", "债务", "工资", "薪资"
            ).any { value.contains(it, true) }

    internal fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
