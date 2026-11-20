package app.nestro.data

import org.json.JSONObject

/** Stable, portable export format for one user-managed memory item. */
object UserMemoryExportCodec {
    const val FORMAT = "nestro-memory"
    const val VERSION = 1

    fun decode(raw: String, targetProjectId: String): UserMemoryEntity {
        require(targetProjectId.isNotBlank() && targetProjectId.length <= 120) { "目标项目 ID 无效" }
        require(raw.length <= 32_768) { "记忆文件超过 32 KB，拒绝导入" }
        val json = JSONObject(raw)
        require(json.getString("format") == FORMAT) { "不是 Nestro 单条记忆文件" }
        require(json.getInt("version") == VERSION) { "不支持的记忆文件版本" }
        val category = json.getString("category")
        require(category in setOf("preference", "workflow", "project", "constraint")) { "记忆分类无效" }
        val content = json.getString("content").trim().replace(Regex("\\s+"), " ")
        require(content.length in 8..180 && UserMemoryStore.isSafeContent(content)) { "记忆内容无效或包含敏感信息" }
        val confidence = json.getDouble("confidence")
        require(confidence.isFinite() && confidence in 0.8..1.0) { "记忆置信度无效" }
        val sourceConversationId = json.getString("source_conversation_id")
        val sourceMessageId = json.getString("source_message_id")
        require(sourceConversationId.isNotBlank() && sourceConversationId.length <= 120) { "来源会话 ID 无效" }
        require(sourceMessageId.isNotBlank() && sourceMessageId.length <= 120) { "来源消息 ID 无效" }
        val createdAt = json.getLong("created_at")
        val updatedAt = json.getLong("updated_at")
        require(createdAt >= 0L && updatedAt >= createdAt) { "记忆时间戳无效" }
        return UserMemoryEntity(
            id = java.util.UUID.randomUUID().toString(),
            projectId = targetProjectId,
            category = category,
            content = content,
            fingerprint = UserMemoryStore.fingerprint(category + "|" + content.lowercase()),
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            confidence = confidence,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    fun encode(memory: UserMemoryEntity): String {
        require(memory.id.isNotBlank() && memory.projectId.isNotBlank() &&
            memory.sourceConversationId.isNotBlank() && memory.sourceMessageId.isNotBlank() &&
            memory.fingerprint.isNotBlank()) { "记忆标识或来源信息无效，拒绝导出" }
        require(memory.category in setOf("preference", "workflow", "project", "constraint")) { "记忆分类无效，拒绝导出" }
        require(memory.content.length in 8..180 && UserMemoryStore.isSafeContent(memory.content)) { "记忆内容无效或包含敏感信息，拒绝导出" }
        require(memory.confidence.isFinite() && memory.confidence in 0.8..1.0) { "记忆置信度无效，拒绝导出" }
        require(memory.createdAt >= 0L && memory.updatedAt >= memory.createdAt) { "记忆时间戳无效，拒绝导出" }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("id", memory.id)
            .put("project_id", memory.projectId)
            .put("category", memory.category)
            .put("content", memory.content)
            .put("confidence", memory.confidence)
            .put("source_conversation_id", memory.sourceConversationId)
            .put("source_message_id", memory.sourceMessageId)
            .put("created_at", memory.createdAt)
            .put("updated_at", memory.updatedAt)
            .toString(2)
    }
}
