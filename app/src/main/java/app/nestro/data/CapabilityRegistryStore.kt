package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CapabilityDescriptor(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val version: String = "1.0",
    val source: String = "builtin",
    val status: String = "已接入",
    val detail: String = "",
    val route: String? = null,
    val available: Boolean = true,
    val projectScoped: Boolean = true,
    val requiresConfirmation: Boolean = true,
    val defaultGranted: Boolean = false
)

data class CapabilityAuditRecord(
    val capabilityId: String,
    val capabilityName: String,
    val projectId: String,
    val action: String,
    val outcome: String,
    val createdAt: Long
)

data class CapabilityToolAuditRecord(
    val serviceId: String,
    val toolName: String,
    val projectId: String,
    val action: String,
    val outcome: String,
    val parameterNames: List<String>,
    val createdAt: Long
)

object CapabilityToolPolicy {
    fun grantKey(serviceId: String, toolName: String): String {
        require(serviceId.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        require(toolName.matches(Regex("[a-zA-Z0-9_.-]{1,100}")))
        return "$serviceId::$toolName"
    }

    fun safeParameterNames(names: List<String>): List<String> = names.asSequence()
        .filter { it.matches(Regex("[a-zA-Z0-9_.-]{1,60}")) }
        .filterNot { it.lowercase() in setOf("token", "access_token", "api_key", "password", "secret", "authorization", "refresh_token", "client_secret") }
        .distinct().take(20).toList()
}

/** Project-scoped capability grants. Permission changes and their audit rows commit atomically. */
object CapabilityRegistryStore {
    private const val REGISTRY = "capability_registry_v1"
    private const val GRANTS = "capability_grants_v1"
    private const val AUDIT = "capability_audit_v1"
    private const val TOOL_GRANTS = "capability_tool_grants_v1"
    private const val TOOL_AUDIT = "capability_tool_audit_v1"
    private const val MAX_CAPABILITIES = 100
    private const val MAX_AUDIT = 200

    private fun validId(value: String) = value.matches(Regex("[a-zA-Z0-9_.-]{1,100}"))
    private fun validProject(value: String) = value.isNotBlank() && value.length <= 120
    private fun objectAt(prefs: SharedPreferences, key: String): JSONObject =
        prefs.getString(key, null)?.let(::JSONObject) ?: JSONObject()
    private fun arrayAt(prefs: SharedPreferences, key: String): JSONArray =
        prefs.getString(key, null)?.let(::JSONArray) ?: JSONArray()

    private fun encode(item: CapabilityDescriptor) = JSONObject()
        .put("id", item.id).put("title", item.title).put("category", item.category)
        .put("description", item.description).put("version", item.version).put("source", item.source)
        .put("status", item.status).put("detail", item.detail).put("route", item.route)
        .put("available", item.available).put("project_scoped", item.projectScoped)
        .put("requires_confirmation", item.requiresConfirmation).put("default_granted", item.defaultGranted)

    private fun decode(row: JSONObject): CapabilityDescriptor? {
        val id = row.optString("id")
        val title = row.optString("title")
        if (!validId(id) || title.isBlank()) return null
        return CapabilityDescriptor(
            id = id, title = title.take(80), category = row.optString("category").take(60),
            description = row.optString("description").take(500), version = row.optString("version", "1.0").take(40),
            source = row.optString("source", "builtin").take(80),
            status = row.optString("status", "已接入").take(30),
            detail = row.optString("detail").take(200),
            route = row.optString("route").takeIf { it.isNotBlank() && it.length <= 120 },
            available = row.optBoolean("available", true),
            projectScoped = row.optBoolean("project_scoped", true),
            requiresConfirmation = row.optBoolean("requires_confirmation", true),
            defaultGranted = row.optBoolean("default_granted", false)
        )
    }

    private fun descriptors(prefs: SharedPreferences): List<CapabilityDescriptor> {
        val rows = arrayAt(prefs, REGISTRY)
        require(rows.length() <= MAX_CAPABILITIES)
        val values = (0 until rows.length()).map { decode(rows.optJSONObject(it) ?: error("能力记录格式无效")) ?: error("能力记录字段无效") }
        require(values.map { it.id }.distinct().size == values.size)
        return values
    }

    fun list(prefs: SharedPreferences): List<CapabilityDescriptor> =
        runCatching { descriptors(prefs) }.getOrDefault(emptyList())

    fun find(prefs: SharedPreferences, id: String): CapabilityDescriptor? =
        runCatching { descriptors(prefs).firstOrNull { it.id == id } }.getOrNull()

    @Synchronized
    fun registerAll(prefs: SharedPreferences, items: List<CapabilityDescriptor>) {
        val merged = descriptors(prefs).associateBy { it.id }.toMutableMap()
        items.forEach { item ->
            require(validId(item.id) && item.title.isNotBlank() && item.title.length <= 80)
            require(item.category.length <= 60 && item.description.length <= 500)
            merged[item.id] = item
        }
        require(merged.size <= MAX_CAPABILITIES)
        val rows = JSONArray().apply { merged.values.sortedBy { it.id }.forEach { put(encode(it)) } }
        check(prefs.edit().putString(REGISTRY, rows.toString()).commit()) { "能力注册信息保存失败" }
    }

    @Synchronized
    fun registerIfAbsent(prefs: SharedPreferences, item: CapabilityDescriptor) {
        // A malformed registry must not crash application startup or be silently overwritten.
        val current = runCatching { descriptors(prefs) }.getOrElse { return }
        if (current.any { it.id == item.id }) return
        registerAll(prefs, listOf(item))
    }

    @Synchronized
    fun unregister(prefs: SharedPreferences, id: String) {
        val current = descriptors(prefs)
        val removed = current.firstOrNull { it.id == id }
        val rows = JSONArray().apply { current.filterNot { it.id == id }.forEach { put(encode(it)) } }
        val grants = objectAt(prefs, GRANTS)
        var audit = arrayAt(prefs, AUDIT)
        val projectIds = grants.keys()
        while (projectIds.hasNext()) {
            val projectId = projectIds.next()
            val project = grants.optJSONObject(projectId) ?: continue
            if (project.has(id)) {
                val wasGranted = project.optBoolean(id, false)
                project.remove(id)
                if (wasGranted && removed != null) {
                    audit = append(audit, capabilityAudit(projectId, removed, "revoke", "capability_unregistered"))
                }
            }
        }
        check(prefs.edit()
            .putString(REGISTRY, rows.toString())
            .putString(GRANTS, grants.toString())
            .putString(AUDIT, audit.toString())
            .commit()) { "能力注册删除及项目授权撤销失败" }
    }

    private fun append(current: JSONArray, row: JSONObject): JSONArray = JSONArray().apply {
        put(row)
        for (i in 0 until minOf(current.length(), MAX_AUDIT - 1)) current.optJSONObject(i)?.let(::put)
    }

    private fun capabilityAudit(projectId: String, item: CapabilityDescriptor, action: String, outcome: String) =
        JSONObject().put("id", UUID.randomUUID().toString()).put("capability_id", item.id)
            .put("capability_name", item.title).put("project_id", projectId)
            .put("action", action.take(40)).put("outcome", outcome.take(40))
            .put("created_at", System.currentTimeMillis())

    fun isGranted(prefs: SharedPreferences, projectId: String, id: String): Boolean {
        if (!validProject(projectId) || !validId(id)) return false
        val item = find(prefs, id) ?: return false
        return runCatching {
            val root = objectAt(prefs, GRANTS)
            val project = root.optJSONObject(projectId)
            if (project?.has(id) == true) project.optBoolean(id, false) else item.defaultGranted
        }.getOrDefault(false)
    }

    @Synchronized
    fun authorize(prefs: SharedPreferences, projectId: String, id: String): Boolean {
        if (!validProject(projectId) || !validId(id)) return false
        val item = find(prefs, id) ?: return false
        val granted = runCatching {
            if (!item.available) false
            else if (!item.projectScoped) true
            else {
                val project = objectAt(prefs, GRANTS).optJSONObject(projectId)
                if (project?.has(id) == true) project.optBoolean(id, false) else item.defaultGranted
            }
        }.getOrDefault(false)
        val persisted = runCatching {
            val rows = append(arrayAt(prefs, AUDIT), capabilityAudit(projectId, item, "invoke", if (granted) "authorized" else "denied"))
            prefs.edit().putString(AUDIT, rows.toString()).commit()
        }.getOrDefault(false)
        return granted && persisted
    }

    @Synchronized
    fun setGranted(prefs: SharedPreferences, projectId: String, id: String, granted: Boolean) {
        require(validProject(projectId) && validId(id)) { "项目 ID 或能力 ID 无效" }
        val item = find(prefs, id) ?: error("能力尚未注册，不能授权")
        require(item.available && item.projectScoped) { "当前能力不可用或不支持项目级授权" }
        val root = objectAt(prefs, GRANTS)
        val project = root.optJSONObject(projectId) ?: JSONObject()
        project.put(id, granted)
        root.put(projectId, project)
        val rows = append(arrayAt(prefs, AUDIT), capabilityAudit(projectId, item, if (granted) "grant" else "revoke", "saved"))
        check(prefs.edit().putString(GRANTS, root.toString()).putString(AUDIT, rows.toString()).commit()) {
            "能力授权及审计记录保存失败"
        }
    }

    fun recentAudit(prefs: SharedPreferences, projectId: String, limit: Int = 30): List<CapabilityAuditRecord> {
        if (!validProject(projectId)) return emptyList()
        return runCatching {
            val rows = arrayAt(prefs, AUDIT)
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                if (row.optString("project_id") != projectId) return@mapNotNull null
                CapabilityAuditRecord(row.optString("capability_id"), row.optString("capability_name"), projectId,
                    row.optString("action"), row.optString("outcome"), row.optLong("created_at"))
            }.take(limit.coerceIn(0, MAX_AUDIT))
        }.getOrDefault(emptyList())
    }

    fun isToolGranted(
        prefs: SharedPreferences,
        projectId: String,
        serviceId: String,
        toolName: String,
        defaultGranted: Boolean = false
    ): Boolean {
        if (!validProject(projectId)) return false
        val key = runCatching { CapabilityToolPolicy.grantKey(serviceId, toolName) }.getOrNull() ?: return false
        return runCatching {
            val project = objectAt(prefs, TOOL_GRANTS).optJSONObject(projectId)
            if (project?.has(key) == true) project.optBoolean(key, false) else defaultGranted
        }.getOrDefault(false)
    }

    private fun toolAudit(
        current: JSONArray, projectId: String, serviceId: String, toolName: String,
        action: String, outcome: String, parameterNames: List<String>
    ): JSONArray {
        val row = JSONObject().put("service_id", serviceId).put("tool_name", toolName)
            .put("project_id", projectId).put("action", action.take(40)).put("outcome", outcome.take(40))
            .put("parameter_names", JSONArray(CapabilityToolPolicy.safeParameterNames(parameterNames)))
            .put("created_at", System.currentTimeMillis())
        return append(current, row)
    }

    @Synchronized
    fun setToolGranted(prefs: SharedPreferences, projectId: String, serviceId: String, toolName: String, granted: Boolean) {
        require(validProject(projectId))
        val key = CapabilityToolPolicy.grantKey(serviceId, toolName)
        val root = objectAt(prefs, TOOL_GRANTS)
        val project = root.optJSONObject(projectId) ?: JSONObject()
        project.put(key, granted)
        root.put(projectId, project)
        val rows = toolAudit(arrayAt(prefs, TOOL_AUDIT), projectId, serviceId, toolName,
            if (granted) "grant" else "revoke", "saved", emptyList())
        check(prefs.edit().putString(TOOL_GRANTS, root.toString()).putString(TOOL_AUDIT, rows.toString()).commit()) {
            "MCP 工具授权及审计记录保存失败"
        }
    }

    @Synchronized
    fun authorizeToolCall(
        prefs: SharedPreferences, projectId: String, serviceId: String, toolName: String,
        parameterNames: List<String> = emptyList(),
        defaultGranted: Boolean = false,
        globallyEnabled: Boolean = true
    ): Boolean {
        if (!validProject(projectId)) return false
        val key = runCatching { CapabilityToolPolicy.grantKey(serviceId, toolName) }.getOrNull() ?: return false
        val granted = globallyEnabled && runCatching {
            val project = objectAt(prefs, TOOL_GRANTS).optJSONObject(projectId)
            if (project?.has(key) == true) project.optBoolean(key, false) else defaultGranted
        }.getOrDefault(false)
        val persisted = runCatching {
            val rows = toolAudit(arrayAt(prefs, TOOL_AUDIT), projectId, serviceId, toolName,
                "invoke", if (granted) "authorized" else "denied", parameterNames)
            prefs.edit().putString(TOOL_AUDIT, rows.toString()).commit()
        }.getOrDefault(false)
        return granted && persisted
    }

    fun recentToolAudit(prefs: SharedPreferences, projectId: String, limit: Int = 30): List<CapabilityToolAuditRecord> {
        if (!validProject(projectId)) return emptyList()
        return runCatching {
            val rows = arrayAt(prefs, TOOL_AUDIT)
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                if (row.optString("project_id") != projectId) return@mapNotNull null
                val names = row.optJSONArray("parameter_names") ?: JSONArray()
                CapabilityToolAuditRecord(row.optString("service_id"), row.optString("tool_name"), projectId,
                    row.optString("action"), row.optString("outcome"),
                    (0 until names.length()).map { names.optString(it) }.filter { it.matches(Regex("[a-zA-Z0-9_.-]{1,60}")) }.take(20),
                    row.optLong("created_at"))
            }.take(limit.coerceIn(0, MAX_AUDIT))
        }.getOrDefault(emptyList())
    }
}
