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
    fun loadProjects(prefs: SharedPreferences): List<ProjectRecord> =
        parseProjects(JSONArray(prefs.getString("projects", "[]") ?: "[]"))

    fun parseProjects(array: JSONArray?): List<ProjectRecord> {
        val parsed = if (array == null) emptyList() else {
            require(array.length() <= 100) { "项目空间数量超过 100 个上限" }
            (0 until array.length()).map { index ->
                val item = array.opt(index) as? JSONObject
                    ?: error("项目空间第 ${index + 1} 项格式无效")
                val id = item.opt("id") as? String
                    ?: error("项目空间 ID 字段无效")
                val name = if (item.has("name")) item.opt("name") as? String
                    ?: error("项目空间名称字段无效") else "项目"
                val instructions = if (item.has("instructions")) item.opt("instructions") as? String
                    ?: error("项目空间指令字段无效") else ""
                val createdAt = item.longOrDefault("created_at", System.currentTimeMillis())
                val archivedAt = item.longOrDefault("archived_at", 0L)
                require(createdAt >= 0L && archivedAt >= 0L) { "项目空间时间字段无效" }
                require(instructions.length <= 4000) { "项目空间指令过长" }
                ProjectRecord(id, name, instructions, createdAt, archivedAt)
            }
        }
        val withDefault = if (parsed.any { it.id == "default" }) parsed
            else listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())) + parsed
        val validation = ProjectSpaceRules.validateCollection(
            withDefault.map { ProjectSpaceRules.Entry(it.id, it.name) }
        )
        require(validation == null) { validation ?: "项目空间数据无效" }
        require(withDefault.all { it.name == it.name.trim() }) { "项目空间名称包含首尾空格" }
        return withDefault
    }

    fun projectsToJson(projects: List<ProjectRecord>): String {
        val validation = ProjectSpaceRules.validateCollection(
            projects.map { ProjectSpaceRules.Entry(it.id, it.name) }
        )
        require(validation == null) { validation ?: "项目空间数据无效" }
        require(projects.all { it.name == it.name.trim() }) { "项目空间名称包含首尾空格" }
        require(projects.all { it.instructions.length <= 4000 }) { "项目空间指令过长" }
        require(projects.all { it.createdAt >= 0L && it.archivedAt >= 0L }) { "项目空间时间字段无效" }

        val array = JSONArray()
        projects.forEach { project ->
            array.put(JSONObject().put("id", project.id).put("name", project.name)
                .put("instructions", project.instructions).put("created_at", project.createdAt)
                .put("archived_at", project.archivedAt))
        }
        return array.toString()
    }

    fun saveProjects(prefs: SharedPreferences, projects: List<ProjectRecord>, markDirty: Boolean = true) {
        val editor = prefs.edit().putString("projects", projectsToJson(projects))
        // Mark every user-initiated change as a pending local snapshot, even after
        // Room has been initialized. A process may die before the Compose snapshot
        // effect commits the same change to Room; startup must then replay this newer
        // preference snapshot instead of restoring the stale Room row.
        if (markDirty) {
            editor.putLong("local_snapshot_saved_at", System.currentTimeMillis())
        }
        check(editor.commit()) { "项目空间保存失败，未确认本地快照已写入" }
    }

    private fun JSONObject.longOrDefault(key: String, default: Long): Long {
        if (!has(key)) return default
        val value = opt(key)
        require(value is Int || value is Long) { "项目空间时间字段必须为整数" }
        return (value as Number).toLong().also {
            require(it >= 0L) { "项目空间时间字段无效" }
        }
    }
}
