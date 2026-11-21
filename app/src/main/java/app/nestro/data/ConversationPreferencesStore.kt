package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ConversationRecord(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val projectId: String = "default",
    val messagesLoaded: Boolean = true
)

internal object ConversationPreferencesStore {
fun loadConversations(prefs: android.content.SharedPreferences): List<ConversationRecord> {
    val rawSnapshot = prefs.getString("conversations_v1", null)
    val stored = if (rawSnapshot == null) emptyList() else {
        val array = try {
            JSONArray(rawSnapshot)
        } catch (failure: Throwable) {
            throw IllegalStateException("本机会话快照格式损坏，已停止回退以保护历史数据", failure)
        }
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val messagesJson = when (val value = item.opt("messages")) {
                null, JSONObject.NULL -> JSONArray()
                is JSONArray -> value
                else -> error("本机会话消息列表格式无效")
            }
            val parsed = (0 until messagesJson.length()).map { messageIndex ->
                parseStoredChatMessage(messagesJson.getJSONObject(messageIndex))
            }
            ConversationRecord(
                item.getString("id"), item.optString("title", "对话"),
                item.optLong("updated_at", System.currentTimeMillis()), parsed,
                item.optString("project_id", "default").ifBlank { "default" }
            )
        }
    }
    if (stored.isNotEmpty()) return stored
    val legacy = loadMessages(prefs)
    val migrated = ConversationRecord(
        UUID.randomUUID().toString(),
        legacy.firstOrNull { it.role == "user" }?.content?.take(24)?.ifBlank { "旧对话" } ?: "旧对话",
        System.currentTimeMillis(), legacy
    )
    val result = listOf(migrated)
    saveConversationList(prefs, result)
    prefs.edit().putString("active_conversation_id", migrated.id).apply()
    return result
}

private fun parseStoredChatMessage(item: JSONObject): ChatMessage {
    val versions = when (val value = item.opt("versions")) {
        null, JSONObject.NULL -> JSONArray()
        is JSONArray -> value
        else -> error("本机回答版本字段格式无效")
    }
    val activeValue = if (item.has("active_version")) item.opt("active_version") else 0
    val activeVersion = (activeValue as? Number)?.toDouble()?.let { value ->
        if (value.isFinite() && value % 1.0 == 0.0 && value in 0.0..Int.MAX_VALUE.toDouble()) value.toInt() else null
    } ?: error("本机回答版本索引类型无效")
    val decodedVersions = decodeMessageVersions(versions.toString(), activeVersion)
    return ChatMessage(
        item.getString("id"), item.getString("role"), item.getString("content"),
        decodedVersions, activeVersion
    )
}

fun saveConversationList(prefs: android.content.SharedPreferences, conversations: List<ConversationRecord>, markDirty: Boolean = true) {
    // Room is authoritative after the one-time migration. Do not overwrite the complete
    // legacy fallback snapshot with the in-memory summary cache.
    if (prefs.getBoolean("room_storage_initialized", false)) return
    require(conversations.size <= 200) { "本机会话数量超过持久化上限" }
    val totalChars = conversations.sumOf { conversation ->
        conversation.title.length.toLong() + conversation.messages.sumOf { message ->
            message.content.length.toLong() + message.versions.sumOf { it.length.toLong() }
        }
    }
    require(totalChars <= 3_500_000L) { "本机会话快照超过安全容量" }
    val array = JSONArray()
    conversations.forEach { conversation ->
        require(conversation.id.isNotBlank() && conversation.title.length <= 80 && conversation.messages.size <= 2_000) {
            "本机会话或消息数量超出持久化上限"
        }
        val messages = JSONArray()
        conversation.messages.forEach { message ->
            require(message.id.isNotBlank() && message.role in setOf("user", "assistant") && message.content.length <= 200_000) {
                "本机消息字段无效"
            }
            messages.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content)
                .put("versions", JSONArray(message.versions).also { encodeMessageVersions(message) })
                .put("active_version", message.activeVersion))
        }
        array.put(JSONObject().put("id", conversation.id).put("title", conversation.title)
            .put("updated_at", conversation.updatedAt).put("project_id", conversation.projectId).put("messages", messages))
    }
    val editor = prefs.edit().putString("conversations_v1", array.toString())
    if (markDirty) editor.putLong("local_snapshot_saved_at", System.currentTimeMillis())
    editor.apply()
}

fun saveConversationMessages(
    prefs: android.content.SharedPreferences,
    activeId: String,
    messages: List<ChatMessage>,
    projectId: String = "default",
    currentConversations: List<ConversationRecord> = emptyList()
): List<ConversationRecord> {
    val existing = if (prefs.getBoolean("room_storage_initialized", false)) {
        currentConversations
    } else {
        loadConversations(prefs)
    }
    val record = existing.firstOrNull { it.id == activeId }
        ?: ConversationRecord(activeId.ifBlank { UUID.randomUUID().toString() }, "新对话", System.currentTimeMillis(), emptyList(), projectId)
    val firstPrompt = messages.firstOrNull { it.role == "user" }?.content?.take(32)?.ifBlank { "新对话" } ?: record.title
    val updated = record.copy(title = if (record.title == "新对话" || record.title == "旧对话") firstPrompt else record.title,
        updatedAt = System.currentTimeMillis(), messages = messages, messagesLoaded = true)
    val next = listOf(updated) + existing.filterNot { it.id == updated.id }
    saveConversationList(prefs, next)
    prefs.edit().putString("active_conversation_id", updated.id).apply()
    return next
}
    fun loadMessages(prefs: SharedPreferences): List<ChatMessage> {
        val array = JSONArray(prefs.getString("messages", "[]") ?: "[]")
        return (0 until array.length()).map { index ->
            parseStoredChatMessage(array.getJSONObject(index))
        }
    }

    fun saveMessages(prefs: SharedPreferences, messages: List<ChatMessage>) {
        require(messages.size <= 200) { "旧版消息快照超过持久化上限" }
        val array = JSONArray()
        messages.forEach { msg ->
            require(msg.id.isNotBlank() && msg.role in setOf("user", "assistant") && msg.content.length <= 200_000) {
                "旧版消息字段无效"
            }
            array.put(JSONObject().put("id", msg.id).put("role", msg.role).put("content", msg.content)
                .put("versions", JSONArray(msg.versions).also { encodeMessageVersions(msg) })
                .put("active_version", msg.activeVersion))
        }
        prefs.edit().putString("messages", array.toString()).apply()
    }

}
