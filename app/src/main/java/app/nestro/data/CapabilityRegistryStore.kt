package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CapabilityAuditRecord(
    val capabilityId: String, val capabilityName: String, val projectId: String,
    val action: String, val outcome: String, val createdAt: Long
)

object CapabilityRegistryStore {
    private const val GRANTS_KEY = "capability_grants_v1"
    private const val AUDIT_KEY = "capability_audit_v1"
    private const val MAX_RECORDS = 200

    fun isGranted(prefs: SharedPreferences, projectId: String, id: String, default: Boolean): Boolean {
        val root = runCatching { JSONObject(prefs.getString(GRANTS_KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val project = root.optJSONObject(projectId) ?: return default
        return if (project.has(id)) project.optBoolean(id, false) else default
    }

    @Synchronized fun setGranted(prefs: SharedPreferences, projectId: String, id: String, granted: Boolean) {
        val root = runCatching { JSONObject(prefs.getString(GRANTS_KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val project = root.optJSONObject(projectId) ?: JSONObject()
        project.put(id, granted); root.put(projectId, project)
        prefs.edit().putString(GRANTS_KEY, root.toString()).apply()
    }

    @Synchronized fun record(prefs: SharedPreferences, projectId: String, id: String, name: String, action: String, outcome: String) {
        val old = runCatching { JSONArray(prefs.getString(AUDIT_KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val next = JSONArray().put(JSONObject().put("id", UUID.randomUUID().toString())
            .put("capability_id", id).put("capability_name", name).put("project_id", projectId)
            .put("action", action).put("outcome", outcome).put("created_at", System.currentTimeMillis()))
        for (i in 0 until minOf(old.length(), MAX_RECORDS - 1)) old.optJSONObject(i)?.let(next::put)
        prefs.edit().putString(AUDIT_KEY, next.toString()).apply()
    }

    fun recentAudit(prefs: SharedPreferences, projectId: String, limit: Int = 30): List<CapabilityAuditRecord> {
        val data = runCatching { JSONArray(prefs.getString(AUDIT_KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("project_id") != projectId) return@mapNotNull null
            CapabilityAuditRecord(o.optString("capability_id"), o.optString("capability_name"),
                o.optString("project_id"), o.optString("action"), o.optString("outcome"), o.optLong("created_at"))
        }.take(limit)
    }
}
