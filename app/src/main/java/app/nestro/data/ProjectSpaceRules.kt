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

    fun validateCollection(entries: List<Entry>): String? {
        if (entries.isEmpty() || entries.size > 100) return "项目空间数量无效，最多保留 100 个"
        if (entries.any { it.id.isBlank() || it.id.length > 120 }) return "项目空间 ID 无效"
        if (entries.map { it.id }.distinct().size != entries.size) return "项目空间 ID 重复"
        if (entries.any { it.name.trim().length !in 1..40 }) return "项目空间名称必须为 1 到 40 个字符"
        if (entries.map { it.name.trim().lowercase() }.distinct().size != entries.size) return "项目空间名称重复"
        if (entries.none { it.id == "default" }) return "默认项目空间缺失"
        return null
    }

    fun canDelete(projectId: String, activeProjectId: String, linkedProjectIds: Set<String>): Boolean =
        projectId.isNotBlank() && projectId != "default" && projectId != activeProjectId && projectId !in linkedProjectIds

    fun canRecoverPendingDeletion(
        projectId: String,
        activeProjectId: String,
        hasRunningRequest: Boolean,
        hasDraft: Boolean,
        hasLocalConversationData: Boolean,
        hasLinkedData: Boolean
    ): Boolean =
        canDelete(projectId, activeProjectId, if (hasLinkedData) setOf(projectId) else emptySet()) &&
            !hasRunningRequest && !hasDraft && !hasLocalConversationData
}
