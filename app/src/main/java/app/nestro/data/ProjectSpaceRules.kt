package app.nestro.data

object ProjectSpaceRules {
    data class Entry(val id: String, val name: String)

    fun validate(name: String, currentId: String, existing: List<Entry>): String? {
        val clean = name.trim()
        if (clean.isBlank() || clean.length > 40) return "项目名称必须为 1 到 40 个字符"
        if (existing.any { it.id != currentId && it.name.trim().equals(clean, ignoreCase = true) }) {
            return "已存在同名项目空间，请更换名称"
        }
        if (currentId.isBlank() && existing.size >= 100) return "项目空间数量已达到 100 个上限"
        return null
    }

    fun canDelete(projectId: String, activeProjectId: String, linkedProjectIds: Set<String>): Boolean =
        projectId.isNotBlank() && projectId != "default" && projectId != activeProjectId && projectId !in linkedProjectIds
}
