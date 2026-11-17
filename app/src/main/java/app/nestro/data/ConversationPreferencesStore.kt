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
    val stored = runCatching {
        val array = JSONArray(prefs.getString("conversations_v1", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val messagesJson = item.optJSONArray("messages") ?: JSONArray()
            val parsed = (0 until messagesJson.length()).map { i ->
                val message = messagesJson.getJSONObject(i)
                ChatMessage(message.getString("id"), message.getString("role"), message.getString("content"), jsonStringList(message.optJSONArray("versions")), message.optInt("active_version", 0))
            }
            ConversationRecord(
                item.getString("id"), item.optString("title", "对话"),
                item.optLong("updated_at", System.currentTimeMillis()), parsed, item.optString("project_id", "default").ifBlank { "default" }
            )
        }
    }.getOrDefault(emptyList())
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

fun saveConversationList(prefs: android.content.SharedPreferences, conversations: List<ConversationRecord>, markDirty: Boolean = true) {
    // Room is authoritative after the one-time migration. Do not overwrite the complete
    // legacy fallback snapshot with the in-memory summary cache.
    if (prefs.getBoolean("room_storage_initialized", false)) return
    val array = JSONArray()
    conversations.take(100).forEach { conversation ->
        val messages = JSONArray()
        conversation.messages.takeLast(500).forEach { message ->
            messages.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content)
                .put("versions", JSONArray(message.versions.take(20))).put("active_version", message.activeVersion.coerceAtLeast(0)))
        }
        array.put(JSONObject().put("id", conversation.id).put("title", conversation.title.take(80))
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
        updatedAt = System.currentTimeMillis(), messages = messages.takeLast(500), messagesLoaded = true)
    val next = listOf(updated) + existing.filterNot { it.id == updated.id }
    saveConversationList(prefs, next)
    prefs.edit().putString("active_conversation_id", updated.id).apply()
    return next
}
    fun loadMessages(prefs: SharedPreferences): List<ChatMessage> = runCatching {
        val array = JSONArray(prefs.getString("messages", "[]"))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            ChatMessage(item.getString("id"), item.getString("role"), item.getString("content"), jsonStringList(item.optJSONArray("versions")), item.optInt("active_version", 0))
        }
    }.getOrDefault(emptyList())

    fun saveMessages(prefs: SharedPreferences, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(200).forEach { msg ->
            array.put(JSONObject().put("id", msg.id).put("role", msg.role).put("content", msg.content).put("versions", JSONArray(msg.versions.take(20))).put("active_version", msg.activeVersion.coerceAtLeast(0)))
        }
        prefs.edit().putString("messages", array.toString()).apply()
    }

    private fun jsonStringList(array: JSONArray?): List<String> =
        array?.let { value -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.take(20) }.orEmpty()

}
