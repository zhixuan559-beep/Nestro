package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal enum class ProjectResourceKind(val key: String) {
    EXPERT("expert"),
    TEAM("team"),
    SKILL("skill"),
    WORKFLOW("workflow"),
    FILE("file")
}

/**
 * Stores explicit project ownership for reusable resources.
 *
 * Missing entries preserve legacy behavior and are global. Once a resource is
 * explicitly scoped, it is visible only in the listed projects. A single "*"
 * entry means global. This allows one resource to be shared by several projects
 * without copying its underlying definition.
 */
internal object ProjectResourceScopeStore {
    private const val PREF_KEY = "project_resource_scopes_v1"
    private const val GLOBAL = "*"
    private const val MAX_RESOURCES = 2000
    private const val MAX_PROJECTS_PER_RESOURCE = 100

    fun load(preferences: SharedPreferences): Map<String, Set<String>> =
        decode(preferences.getString(PREF_KEY, "{}") ?: "{}")

    fun decode(raw: String): Map<String, Set<String>> {
        val json = JSONObject(raw)
        require(json.length() <= MAX_RESOURCES) { "项目资源关联数量超过上限" }
        val result = linkedMapOf<String, Set<String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            validateResourceKey(key)
            val values = json.opt(key) as? JSONArray ?: error("项目资源范围格式无效")
            require(values.length() <= MAX_PROJECTS_PER_RESOURCE) { "单项资源关联项目过多" }
            val scopes = (0 until values.length()).map { index ->
                val projectId = values.opt(index) as? String ?: error("项目资源范围必须为字符串")
                require(projectId == GLOBAL || projectId.isNotBlank()) { "项目资源范围 ID 无效" }
                projectId
            }
            require(scopes.distinct().size == scopes.size) { "项目资源范围存在重复项" }
            require(GLOBAL !in scopes || scopes.size == 1) { "全局资源不能同时声明项目范围" }
            result[key] = scopes.toSet()
        }
        return result
    }

    fun encode(scopes: Map<String, Set<String>>): String {
        require(scopes.size <= MAX_RESOURCES) { "项目资源关联数量超过上限" }
        val json = JSONObject()
        scopes.forEach { (key, projects) ->
            validateResourceKey(key)
            require(projects.size <= MAX_PROJECTS_PER_RESOURCE) { "单项资源关联项目过多" }
            require(projects.all { it == GLOBAL || it.isNotBlank() }) { "项目资源范围 ID 无效" }
            require(projects.size == projects.distinct().size) { "项目资源范围存在重复项" }
            require(GLOBAL !in projects || projects.size == 1) { "全局资源不能同时声明项目范围" }
            val values = JSONArray().apply { projects.sorted().forEach { put(it) } }
            json.put(key, values)
        }
        return json.toString()
    }

    private fun validateResourceKey(key: String) {
        require(key.length in 3..240 && key.contains(':')) { "项目资源标识无效" }
        val kindKey = key.substringBefore(':')
        val resourceId = key.substringAfter(':')
        require(ProjectResourceKind.entries.any { it.key == kindKey } &&
            resourceId.isNotBlank() && resourceId.length <= 200) { "项目资源类型或 ID 无效" }
    }

    fun isVisible(scopes: Map<String, Set<String>>, kind: ProjectResourceKind, resourceId: String, projectId: String): Boolean {
        if (resourceId.isBlank() || projectId.isBlank()) return false
        val allowedProjects = scopes[resourceKey(kind, resourceId)] ?: return true
        return GLOBAL in allowedProjects || projectId in allowedProjects
    }

    fun isVisibleFromProjects(
        scopes: Map<String, Set<String>>,
        kind: ProjectResourceKind,
        resourceId: String,
        activeProjectId: String,
        additionallyReadableProjectIds: Set<String>
    ): Boolean {
        if (resourceId.isBlank() || activeProjectId.isBlank()) return false
        val allowedProjects = scopes[resourceKey(kind, resourceId)] ?: return true
        return GLOBAL in allowedProjects || activeProjectId in allowedProjects ||
            allowedProjects.any { it in additionallyReadableProjectIds }
    }

    fun isTeamVisibleFromProjects(
        scopes: Map<String, Set<String>>,
        teamId: String,
        memberExpertIds: List<String>,
        activeProjectId: String,
        additionallyReadableProjectIds: Set<String>
    ): Boolean {
        if (memberExpertIds.size !in 2..4 || memberExpertIds.distinct().size != memberExpertIds.size) return false
        return isVisibleFromProjects(
            scopes, ProjectResourceKind.TEAM, teamId, activeProjectId, additionallyReadableProjectIds
        ) && memberExpertIds.all { memberId ->
            isVisibleFromProjects(
                scopes, ProjectResourceKind.EXPERT, memberId, activeProjectId, additionallyReadableProjectIds
            )
        }
    }

    /** Null is legacy-global; "*" is explicitly global; empty means orphaned. */
    fun ownerProjectIds(scopes: Map<String, Set<String>>, kind: ProjectResourceKind, resourceId: String): Set<String>? =
        scopes[resourceKey(kind, resourceId)]

    /** Require WRITE from every foreign owner before mutating a shared resource. */
    fun canWriteFromProject(
        scopes: Map<String, Set<String>>,
        kind: ProjectResourceKind,
        resourceId: String,
        activeProjectId: String,
        writableSourceProjectIds: Set<String>
    ): Boolean {
        if (resourceId.isBlank() || activeProjectId.isBlank()) return false
        val owners = ownerProjectIds(scopes, kind, resourceId) ?: return true
        if (GLOBAL in owners) return true
        if (owners.isEmpty()) return false
        return owners.filterNot { it == activeProjectId }.all { it in writableSourceProjectIds }
    }

    /** Assign scope for a newly-created resource without overwriting a stale/shared key. */
    fun assignNewResourceToProject(
        scopes: Map<String, Set<String>>,
        kind: ProjectResourceKind,
        resourceId: String,
        projectId: String
    ): Map<String, Set<String>> {
        require(resourceId.isNotBlank() && resourceId.length <= 200) { "资源 ID 无效" }
        require(projectId.isNotBlank() && projectId != GLOBAL) { "项目 ID 无效" }
        val key = resourceKey(kind, resourceId)
        require(key !in scopes) { "新资源范围已存在，拒绝覆盖" }
        return setProjectScope(scopes, kind, resourceId, setOf(projectId))
    }

    fun setProjectScope(
        scopes: Map<String, Set<String>>,
        kind: ProjectResourceKind,
        resourceId: String,
        projectIds: Set<String>
    ): Map<String, Set<String>> {
        require(resourceId.isNotBlank() && resourceId.length <= 200) { "资源 ID 无效" }
        require(projectIds.isNotEmpty() && projectIds.size <= MAX_PROJECTS_PER_RESOURCE) { "至少需要关联一个项目" }
        require(projectIds.all { it.isNotBlank() && it != GLOBAL }) { "项目 ID 无效" }
        return scopes + (resourceKey(kind, resourceId) to projectIds)
    }

    fun setGlobal(
        scopes: Map<String, Set<String>>,
        kind: ProjectResourceKind,
        resourceId: String
    ): Map<String, Set<String>> {
        require(resourceId.isNotBlank() && resourceId.length <= 200) { "资源 ID 无效" }
        return scopes + (resourceKey(kind, resourceId) to setOf(GLOBAL))
    }

    fun renameResourceScope(
        scopes: Map<String, Set<String>>,
        kind: ProjectResourceKind,
        oldResourceId: String,
        newResourceId: String
    ): Map<String, Set<String>> {
        require(oldResourceId.isNotBlank() && newResourceId.isNotBlank() &&
            oldResourceId.length <= 200 && newResourceId.length <= 200 && oldResourceId != newResourceId) {
            "资源重命名参数无效"
        }
        val oldKey = resourceKey(kind, oldResourceId)
        val newKey = resourceKey(kind, newResourceId)
        require(newKey !in scopes) { "目标资源范围已存在，拒绝覆盖" }
        val owners = scopes[oldKey] ?: return scopes
        return (scopes - oldKey) + (newKey to owners)
    }

    fun removeResourceScope(
        scopes: Map<String, Set<String>>,
        kind: ProjectResourceKind,
        resourceId: String
    ): Map<String, Set<String>> {
        require(resourceId.isNotBlank() && resourceId.length <= 200) { "资源 ID 无效" }
        return scopes - resourceKey(kind, resourceId)
    }

    fun duplicateProjectScope(
        scopes: Map<String, Set<String>>,
        sourceProjectId: String,
        targetProjectId: String
    ): Map<String, Set<String>> {
        require(sourceProjectId.isNotBlank() && targetProjectId.isNotBlank() && sourceProjectId != targetProjectId) {
            "复制项目范围参数无效"
        }
        return scopes.mapValues { (_, projects) ->
            if (GLOBAL in projects || sourceProjectId !in projects) projects else projects + targetProjectId
        }
    }

    fun resourcesOwnedOnlyByProject(
        scopes: Map<String, Set<String>>,
        projectId: String
    ): Set<String> {
        require(projectId.isNotBlank() && projectId != GLOBAL) { "项目 ID 无效" }
        return scopes.filterValues { owners -> owners == setOf(projectId) }.keys
    }

    fun removeProjectScope(
        scopes: Map<String, Set<String>>,
        projectId: String
    ): Map<String, Set<String>> {
        require(projectId.isNotBlank() && projectId != GLOBAL) { "待移除项目 ID 无效" }
        return scopes.mapValues { (_, projects) ->
            if (GLOBAL in projects || projectId !in projects) projects
            else projects - projectId
        }
    }

    fun save(preferences: SharedPreferences, scopes: Map<String, Set<String>>) {
        check(preferences.edit().putString(PREF_KEY, encode(scopes)).commit()) { "项目资源范围保存失败" }
    }

    fun resourceKey(kind: ProjectResourceKind, resourceId: String): String =
        "${kind.key}:$resourceId"
}
