package app.nestro.data

import org.json.JSONObject

/** Stable, portable export format for one user-managed memory item. */
object UserMemoryExportCodec {
    const val FORMAT = "nestro-memory"
    const val VERSION = 1

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
