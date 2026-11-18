package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Directional, fail-closed and auditable cross-project grants. */
internal object ProjectAccessControlStore {
    enum class Access(val key: String) { READ("read"), WRITE("write") }
    data class Grant(val sourceProjectId: String, val targetProjectId: String, val access: Set<Access>, val grantedAt: Long)
    data class AuditEntry(val at: Long, val sourceProjectId: String, val targetProjectId: String, val access: Access, val allowed: Boolean, val reason: String)

    private const val GRANTS_KEY = "project_access_grants_v1"
    private const val AUDIT_KEY = "project_access_audit_v1"
    private const val MAX_GRANTS = 500
    private const val MAX_AUDIT = 500
    private const val MAX_ID = 120

    fun decodeGrants(raw: String): List<Grant> {
        val rows = JSONArray(raw)
        require(rows.length() <= MAX_GRANTS) { "跨项目授权数量超过上限" }
        val result = (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: error("跨项目授权记录格式无效")
            val source = row.opt("source") as? String ?: error("授权来源项目无效")
            val target = row.opt("target") as? String ?: error("授权目标项目无效")
            require(source.isNotBlank() && target.isNotBlank() && source != target && source.length <= MAX_ID && target.length <= MAX_ID) { "授权项目 ID 无效" }
            val values = row.optJSONArray("access") ?: error("授权范围格式无效")
            val access = (0 until values.length()).map { i ->
                when (values.opt(i)) { "read" -> Access.READ; "write" -> Access.WRITE; else -> error("授权范围包含未知权限") }
            }.toSet()
            require(access.isNotEmpty() && access.size == values.length()) { "授权范围为空或存在重复项" }
            val timestamp = row.opt("granted_at") as? Number ?: error("授权时间无效")
            require(timestamp.toDouble().isFinite() && timestamp.toDouble() % 1.0 == 0.0 && timestamp.toLong() >= 0L) { "授权时间无效" }
            Grant(source, target, access, timestamp.toLong())
        }
        require(result.map { it.sourceProjectId to it.targetProjectId }.distinct().size == result.size) { "同一项目对存在重复授权" }
        return result
    }

    fun encodeGrants(grants: List<Grant>): String {
        require(grants.size <= MAX_GRANTS) { "跨项目授权数量超过上限" }
        require(grants.map { it.sourceProjectId to it.targetProjectId }.distinct().size == grants.size) { "同一项目对不能重复授权" }
        val rows = JSONArray()
        grants.forEach { grant ->
            require(grant.sourceProjectId.isNotBlank() && grant.targetProjectId.isNotBlank() && grant.sourceProjectId != grant.targetProjectId &&
                grant.sourceProjectId.length <= MAX_ID && grant.targetProjectId.length <= MAX_ID && grant.access.isNotEmpty() && grant.grantedAt >= 0L) { "授权参数无效" }
            rows.put(JSONObject().put("source", grant.sourceProjectId).put("target", grant.targetProjectId)
                .put("access", JSONArray().apply { grant.access.sortedBy { it.key }.forEach { put(it.key) } })
                .put("granted_at", grant.grantedAt))
        }
        return rows.toString()
    }

    fun loadGrants(preferences: SharedPreferences): List<Grant> =
        decodeGrants(preferences.getString(GRANTS_KEY, "[]") ?: "[]")

    /** Caller must obtain an explicit user confirmation before invoking this method. */
    fun grant(preferences: SharedPreferences, sourceProjectId: String, targetProjectId: String, access: Set<Access>, at: Long = System.currentTimeMillis()) {
        require(sourceProjectId.isNotBlank() && targetProjectId.isNotBlank() && sourceProjectId != targetProjectId && access.isNotEmpty() && at >= 0L) { "跨项目授权参数无效" }
        val currentGrants = loadGrants(preferences)
        val currentAudit = loadAudit(preferences)
        val nextGrants = currentGrants.filterNot { it.sourceProjectId == sourceProjectId && it.targetProjectId == targetProjectId } +
            Grant(sourceProjectId, targetProjectId, access, at)
        val nextAudit = appendAuditEntries(currentAudit, access.sortedBy { it.key }.map { permission ->
            AuditEntry(at, sourceProjectId, targetProjectId, permission, true, "grant")
        })
        // Persist the permission and its complete audit trail atomically. A grant
        // must never become active if its audit record cannot be committed.
        check(preferences.edit()
            .putString(GRANTS_KEY, encodeGrants(nextGrants))
            .putString(AUDIT_KEY, encodeAudit(nextAudit))
            .commit()) { "跨项目授权及审计记录保存失败" }
    }

    fun revoke(preferences: SharedPreferences, sourceProjectId: String, targetProjectId: String, at: Long = System.currentTimeMillis()) {
        require(sourceProjectId.isNotBlank() && targetProjectId.isNotBlank() && sourceProjectId != targetProjectId && at >= 0L) { "跨项目授权参数无效" }
        val currentGrants = loadGrants(preferences)
        val currentAudit = loadAudit(preferences)
        val existing = currentGrants.firstOrNull { it.sourceProjectId == sourceProjectId && it.targetProjectId == targetProjectId }
        val nextGrants = currentGrants.filterNot { it.sourceProjectId == sourceProjectId && it.targetProjectId == targetProjectId }
        val revokedPermissions = existing?.access?.sortedBy { it.key } ?: listOf(Access.READ)
        val nextAudit = appendAuditEntries(currentAudit, revokedPermissions.map { permission ->
            AuditEntry(at, sourceProjectId, targetProjectId, permission, true, if (existing == null) "revoke:no_existing_grant" else "revoke")
        })
        check(preferences.edit()
            .putString(GRANTS_KEY, encodeGrants(nextGrants))
            .putString(AUDIT_KEY, encodeAudit(nextAudit))
            .commit()) { "跨项目授权撤销及审计记录保存失败" }
    }

    /** Missing or malformed grants deny access; inability to persist the decision also denies access. */
    fun authorize(preferences: SharedPreferences, sourceProjectId: String, targetProjectId: String, access: Access, at: Long = System.currentTimeMillis()): Boolean {
        require(sourceProjectId.isNotBlank() && targetProjectId.isNotBlank() && sourceProjectId != targetProjectId && at >= 0L) { "跨项目访问参数无效" }
        val grants = runCatching { loadGrants(preferences) }
        val grant = grants.getOrNull()?.firstOrNull { it.sourceProjectId == sourceProjectId && it.targetProjectId == targetProjectId }
        val allowed = grant?.access?.contains(access) == true
        val currentAudit = runCatching { loadAudit(preferences) }.getOrNull() ?: return false
        val reason = if (grants.isFailure) "invalid_grant_store" else if (allowed) "explicit_grant" else "no_matching_grant"
        val nextAudit = appendAuditEntries(currentAudit, listOf(AuditEntry(at, sourceProjectId, targetProjectId, access, allowed, reason)))
        val persisted = runCatching {
            preferences.edit().putString(AUDIT_KEY, encodeAudit(nextAudit)).commit()
        }.getOrDefault(false)
        return allowed && persisted
    }

    fun grantedReadableSourceProjects(preferences: SharedPreferences, targetProjectId: String): Set<String> {
        if (targetProjectId.isBlank()) return emptySet()
        return runCatching { loadGrants(preferences) }.getOrDefault(emptyList())
            .filter { it.targetProjectId == targetProjectId && Access.READ in it.access }
            .map { it.sourceProjectId }.toSet()
    }

    /** Returns source projects whose READ grant explicitly permits the target project to retrieve resources. */
    fun readableSourceProjects(
        preferences: SharedPreferences,
        targetProjectId: String,
        candidateSourceProjectIds: Set<String>,
        at: Long = System.currentTimeMillis()
    ): Set<String> {
        require(targetProjectId.isNotBlank()) { "目标项目 ID 无效" }
        val grants = runCatching { loadGrants(preferences) }.getOrDefault(emptyList())
        return grants.asSequence()
            .filter { it.sourceProjectId in candidateSourceProjectIds && it.targetProjectId == targetProjectId && Access.READ in it.access }
            .map { it.sourceProjectId }
            .filter { sourceProjectId -> authorize(preferences, sourceProjectId, targetProjectId, Access.READ, at) }
            .toSet()
    }

    fun loadAudit(preferences: SharedPreferences): List<AuditEntry> =
        decodeAudit(preferences.getString(AUDIT_KEY, "[]") ?: "[]")

    internal fun decodeAudit(raw: String): List<AuditEntry> {
        val rows = JSONArray(raw)
        require(rows.length() <= MAX_AUDIT) { "跨项目审计记录数量超过上限" }
        return (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: error("跨项目审计记录格式无效")
            val access = Access.values().firstOrNull { it.key == row.optString("access") } ?: error("跨项目审计权限无效")
            val source = row.optString("source")
            val target = row.optString("target")
            val reason = row.optString("reason")
            val timestamp = row.opt("at") as? Number ?: error("跨项目审计时间无效")
            require(source.isNotBlank() && target.isNotBlank() && source != target && source.length <= MAX_ID &&
                target.length <= MAX_ID && reason.length <= 160 && timestamp.toDouble().isFinite() &&
                timestamp.toDouble() % 1.0 == 0.0 && timestamp.toLong() >= 0L) { "跨项目审计字段无效" }
            AuditEntry(timestamp.toLong(), source, target, access, row.opt("allowed") as? Boolean ?: error("跨项目审计结果无效"), reason)
        }
    }

    private fun encodeAudit(entries: List<AuditEntry>): String {
        require(entries.size <= MAX_AUDIT) { "跨项目审计记录数量超过上限" }
        val rows = JSONArray()
        entries.forEach { item ->
            require(item.at >= 0L && item.sourceProjectId.isNotBlank() && item.targetProjectId.isNotBlank() &&
                item.sourceProjectId != item.targetProjectId && item.sourceProjectId.length <= MAX_ID &&
                item.targetProjectId.length <= MAX_ID && item.reason.length <= 160) { "跨项目审计记录无效" }
            rows.put(JSONObject().put("at", item.at).put("source", item.sourceProjectId).put("target", item.targetProjectId)
                .put("access", item.access.key).put("allowed", item.allowed).put("reason", item.reason))
        }
        return rows.toString()
    }

    private fun appendAuditEntries(current: List<AuditEntry>, additions: List<AuditEntry>): List<AuditEntry> =
        (current + additions).takeLast(MAX_AUDIT)

}
