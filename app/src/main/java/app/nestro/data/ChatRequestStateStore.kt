package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONObject

data class ChatRequestState(
    val status: String,
    val requestId: String,
    val conversationId: String,
    val projectId: String,
    val prompt: String,
    val partialAnswer: String,
    val startedAt: Long
)

object ChatRequestStateStore {
    private const val KEY = "chat_request_state_v1"

    fun save(prefs: SharedPreferences, requestId: String, conversationId: String, projectId: String, prompt: String, partialAnswer: String, startedAt: Long, status: String = "running") {
        prefs.edit().putString(KEY, JSONObject()
            .put("status", status).put("request_id", requestId).put("conversation_id", conversationId)
            .put("project_id", projectId).put("prompt", prompt).put("partial_answer", partialAnswer)
            .put("started_at", startedAt).toString()).apply()
    }

    fun load(prefs: SharedPreferences): ChatRequestState? {
        val o = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrNull() ?: return null
        if (o.optString("request_id").isBlank()) return null
        return ChatRequestState(o.optString("status","running"), o.optString("request_id"), o.optString("conversation_id"),
            o.optString("project_id","default"), o.optString("prompt"), o.optString("partial_answer"), o.optLong("started_at",0L))
    }

    fun clear(prefs: SharedPreferences) { prefs.edit().remove(KEY).apply() }
}
