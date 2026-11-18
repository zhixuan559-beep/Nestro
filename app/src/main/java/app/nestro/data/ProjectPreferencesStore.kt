package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class ProjectRecord(
    val id: String,
    val name: String,
    val instructions: String,
    val createdAt: Long,
    val archivedAt: Long = 0L
)

internal object ProjectPreferencesStore {
fun loadProjects(prefs: android.content.SharedPreferences): List<ProjectRecord> {
    val parsed = runCatching {
        val array = JSONArray(prefs.getString("projects", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ProjectRecord(item.getString("id"), item.optString("name", "项目").take(40),
                item.optString("instructions", "").take(4000), item.optLong("created_at", System.currentTimeMillis()), item.optLong("archived_at", 0L).coerceAtLeast(0L))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }.getOrDefault(emptyList())
    val all = if (parsed.any { it.id == "default" }) parsed else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed
    return all.distinctBy { it.id }.take(100)
}

fun parseProjects(array: JSONArray?): List<ProjectRecord> {
    val parsed = if (array == null) emptyList() else (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ProjectRecord(
            item.getString("id"),
            item.optString("name", "项目"),
            item.optString("instructions", ""),
            item.optLong("created_at", System.currentTimeMillis()),
            item.optLong("archived_at", 0L).coerceAtLeast(0L)
        )
    }
    val withDefault = if (parsed.any { it.id == "default" }) parsed
        else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed
    require(withDefault.all { it.instructions.length <= 4000 }) { "项目空间指令过长" }
    require(ProjectSpaceRules.validateCollection(withDefault.map { ProjectSpaceRules.Entry(it.id, it.name) }) == null) {
        ProjectSpaceRules.validateCollection(withDefault.map { ProjectSpaceRules.Entry(it.id, it.name) }) ?: "项目空间数据无效"
    }
    return withDefault
}

fun projectsToJson(projects: List<ProjectRecord>): String {
    val validation = ProjectSpaceRules.validateCollection(
        projects.map { ProjectSpaceRules.Entry(it.id, it.name) }
    )
    require(validation == null) { validation ?: "项目空间数据无效" }
    require(projects.all { it.instructions.length <= 4000 }) { "项目空间指令过长" }

    val array = JSONArray()
    projects.forEach { project ->
        array.put(JSONObject().put("id", project.id).put("name", project.name.trim())
            .put("instructions", project.instructions).put("created_at", project.createdAt)
            .put("archived_at", project.archivedAt.coerceAtLeast(0L)))
    }
    return array.toString()
}

fun saveProjects(prefs: android.content.SharedPreferences, projects: List<ProjectRecord>, markDirty: Boolean = true) {
    val editor = prefs.edit().putString("projects", projectsToJson(projects))
    if (markDirty && !prefs.getBoolean("room_storage_initialized", false)) {
        editor.putLong("local_snapshot_saved_at", System.currentTimeMillis())
    }
    editor.apply()
}
}
