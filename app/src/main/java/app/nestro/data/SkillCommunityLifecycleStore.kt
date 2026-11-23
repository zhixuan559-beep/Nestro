package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Atomic local lifecycle for verified community skills.
 * Previous versions are retained for rollback; no permission is granted here.
 */
class SkillCommunityLifecycleStore(private val preferences: SharedPreferences) {
    fun install(bundle: SkillBundle) = installAll(listOf(bundle))

    /** Validates and commits the complete dependency plan in one SharedPreferences transaction. */
    fun installAll(bundles: List<SkillBundle>) {
        require(bundles.isNotEmpty() && bundles.size <= 50) { "安装计划为空或超过 50 个技能" }
        require(bundles.map { it.name }.distinct().size == bundles.size) { "安装计划包含重复技能" }
        require(bundles.all { it.source == "已验证签名社区目录" }) { "仅允许安装经过签名目录验证的社区技能" }
        val skills = JSONObject(preferences.getString("skills", "{}") ?: "{}")
        val metadata = JSONObject(preferences.getString("skill_metadata_v1", "{}") ?: "{}")
        SkillBundleParser.validateStoredCollection(skills.toString(), metadata.toString())
        val disabled = disabledSkills()
        val disabledNames = (0 until disabled.length()).map { disabled.optString(it) }.toSet()
        val currentSkills = skills.keys().asSequence().associateWith { name -> skills.optString(name) }
        val currentMetadata = metadata.keys().asSequence().associateWith { name -> metadata.optString(name) }
        val history = historyJson()
        val planned = bundles.associateBy { it.name }
        val currentVersions = skills.keys().asSequence().associateWith { name ->
            val raw = metadata.optString(name, "")
            if (raw.isBlank()) "1.0.0" else JSONObject(raw).optString("version", "1.0.0").ifBlank { "1.0.0" }
        }.toMutableMap()
        bundles.forEach { bundle ->
            require(runCatching { SkillSemVer.compare(bundle.version, bundle.version) }.isSuccess) { "社区技能版本不是有效 SemVer 2.0" }
            bundle.dependencies.forEach { dependency ->
                require(runCatching { SkillSemVer.matches("1.0.0", dependency.version) }.isSuccess) { "技能依赖版本范围无效：${dependency.name}" }
            }
            val oldMeta = runCatching { JSONObject(metadata.optString(bundle.name, "{}")) }.getOrDefault(JSONObject())
            val oldPrompt = skills.optString(bundle.name, null)
            if (oldPrompt != null && oldMeta.optString("source") != "已验证签名社区目录") error("同名本地技能已存在；为防止覆盖，已拒绝安装：${bundle.name}")
            if (oldPrompt != null) require(SkillSemVer.compare(bundle.version, oldMeta.optString("version")) > 0) { "仅允许安装高于当前社区版本的更新：${bundle.name}" }
            currentVersions[bundle.name] = bundle.version
        }
        bundles.forEach { bundle -> bundle.dependencies.forEach { dependency ->
            val available = currentVersions[dependency.name] ?: error("缺少技能依赖：${dependency.name}")
            require(SkillSemVer.matches(available, dependency.version)) { "依赖版本不匹配：${dependency.name} 需要 ${dependency.version}，计划版本为 $available" }
            if (dependency.name in currentSkills) {
                require(dependency.name !in disabledNames) { "依赖技能已停用，请先启用：${dependency.name}" }
                if (dependency.name !in planned) {
                    val issues = SkillDependencyResolver.inspectInstalledSkill(
                        dependency.name, currentSkills, currentMetadata, disabledNames
                    )
                    require(issues.isEmpty()) {
                        "已安装依赖的完整依赖链不可用：${dependency.name}；${issues.joinToString("；")}"
                    }
                }
            }
        } }
        // Existing dependents must remain compatible with every changed package.
        metadata.keys().forEach { dependentName ->
            if (dependentName in planned) return@forEach
            val row = JSONObject(metadata.optString(dependentName, "{}"))
            val deps = row.optJSONArray("dependencies") ?: return@forEach
            for (i in 0 until deps.length()) {
                val dep = deps.optJSONObject(i) ?: continue
                val changed = planned[dep.optString("name")] ?: continue
                require(SkillSemVer.matches(changed.version, dep.optString("version", "*"))) { "更新会破坏已有技能依赖：$dependentName 要求 ${changed.name} ${dep.optString("version", "*")}" }
            }
        }
        val graph = mutableMapOf<String, List<String>>()
        metadata.keys().forEach { name ->
            val row = runCatching { JSONObject(metadata.optString(name, "{}")) }.getOrDefault(JSONObject())
            val deps = row.optJSONArray("dependencies") ?: JSONArray()
            graph[name] = (0 until deps.length()).mapNotNull { deps.optJSONObject(it)?.optString("name") }
        }
        bundles.forEach { graph[it.name] = it.dependencies.map(SkillDependency::name) }
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun visit(name: String) {
            require(name !in visiting) { "技能依赖存在循环引用：$name" }
            if (!visited.add(name)) return
            visiting.add(name)
            graph[name].orEmpty().forEach { dependency -> if (dependency in graph) visit(dependency) }
            visiting.remove(name)
        }
        graph.keys.forEach(::visit)
        bundles.forEach { bundle ->
            val oldPrompt = skills.optString(bundle.name, null)
            val oldMeta = metadata.optString(bundle.name, null)
            val snapshots = history.optJSONArray(bundle.name) ?: JSONArray()
            snapshots.put(JSONObject().put("hadSkill", oldPrompt != null).put("prompt", oldPrompt ?: JSONObject.NULL)
                .put("metadata", oldMeta ?: JSONObject.NULL).put("wasDisabled", isDisabled(bundle.name)).put("savedAt", System.currentTimeMillis()))
            while (snapshots.length() > MAX_HISTORY) snapshots.remove(0)
            history.put(bundle.name, snapshots)
            skills.put(bundle.name, bundle.prompt)
            metadata.put(bundle.name, SkillBundleParser.metadataJson(bundle))
            if (oldPrompt == null && !isDisabled(bundle.name)) disabled.put(bundle.name)
        }
        commit(skills, metadata, history, disabled)
    }
    fun uninstall(name: String) {
        require(name.isNotBlank() && name.length <= 40)
        val skills = JSONObject(preferences.getString("skills", "{}") ?: "{}")
        val metadata = JSONObject(preferences.getString("skill_metadata_v1", "{}") ?: "{}")
        SkillBundleParser.validateStoredCollection(skills.toString(), metadata.toString())
        val currentMeta = JSONObject(metadata.optString(name, "{}"))
        require(currentMeta.optString("source") == "已验证签名社区目录") { "仅可从社区管理器卸载社区技能" }
        metadata.keys().forEach { dependentName ->
            if (dependentName == name) return@forEach
            val dependent = JSONObject(metadata.optString(dependentName, "{}"))
            val deps = dependent.optJSONArray("dependencies") ?: return@forEach
            require((0 until deps.length()).none { deps.optJSONObject(it)?.optString("name") == name }) { "该技能仍被 $dependentName 依赖，不能直接卸载" }
        }
        val history = historyJson()
        val snapshots = history.optJSONArray(name) ?: JSONArray()
        snapshots.put(JSONObject()
            .put("hadSkill", true)
            .put("prompt", skills.getString(name))
            .put("metadata", metadata.getString(name))
            .put("wasDisabled", isDisabled(name))
            .put("savedAt", System.currentTimeMillis()))
        while (snapshots.length() > MAX_HISTORY) snapshots.remove(0)
        history.put(name, snapshots)
        skills.remove(name)
        metadata.remove(name)
        val disabled = JSONArray().apply {
            val current = disabledSkills()
            for (index in 0 until current.length()) {
                val value = current.optString(index)
                if (value != name) put(value)
            }
        }
        commit(skills, metadata, history, disabled)
    }

    fun rollback(name: String) {
        val skills = JSONObject(preferences.getString("skills", "{}") ?: "{}")
        val metadata = JSONObject(preferences.getString("skill_metadata_v1", "{}") ?: "{}")
        SkillBundleParser.validateStoredCollection(skills.toString(), metadata.toString())
        val history = historyJson()
        val snapshots = history.optJSONArray(name) ?: error("没有可回退的历史版本")
        require(snapshots.length() > 0) { "没有可回退的历史版本" }
        val previous = snapshots.getJSONObject(snapshots.length() - 1)
        val restoredVersion = if (previous.optBoolean("hadSkill")) runCatching { JSONObject(previous.getString("metadata")).optString("version") }.getOrDefault("") else ""
        metadata.keys().forEach { dependentName ->
            if (dependentName == name) return@forEach
            val dependent = JSONObject(metadata.optString(dependentName, "{}"))
            val deps = dependent.optJSONArray("dependencies") ?: return@forEach
            for (i in 0 until deps.length()) {
                val dep = deps.optJSONObject(i) ?: continue
                if (dep.optString("name") == name) {
                    require(restoredVersion.isNotBlank() && SkillSemVer.matches(restoredVersion, dep.optString("version", "*"))) {
                        "回退会破坏已有技能依赖：$dependentName 要求 $name ${dep.optString("version", "*")}"
                    }
                }
            }
        }
        snapshots.remove(snapshots.length() - 1)
        val disabled = setDisabled(
            name,
            previous.optBoolean("hadSkill") && previous.optBoolean("wasDisabled", true)
        )
        if (previous.optBoolean("hadSkill")) {
            skills.put(name, previous.getString("prompt"))
            metadata.put(name, previous.getString("metadata"))
        } else {
            skills.remove(name)
            metadata.remove(name)
        }
        if (snapshots.length() == 0) history.remove(name) else history.put(name, snapshots)
        commit(skills, metadata, history, disabled)
    }

    fun canRollback(name: String): Boolean = (historyJson().optJSONArray(name)?.length() ?: 0) > 0

    private fun isDisabled(name: String): Boolean {
        val current = disabledSkills()
        return (0 until current.length()).any { current.optString(it) == name }
    }

    private fun setDisabled(name: String, disabled: Boolean): JSONArray {
        val result = JSONArray()
        val current = disabledSkills()
        for (index in 0 until current.length()) {
            val value = current.optString(index)
            if (value != name) result.put(value)
        }
        if (disabled) result.put(name)
        return result
    }

    private fun disabledSkills(): JSONArray {
        val rows = JSONArray(preferences.getString("disabled_skills_v1", "[]") ?: "[]")
        require(rows.length() <= 500) { "停用技能列表超过 500 项" }
        val seen = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val name = rows.optString(index).trim()
            require(name.isNotBlank() && name.length <= 40 && seen.add(name)) { "停用技能列表损坏" }
        }
        return rows
    }

    private fun historyJson(): JSONObject = runCatching {
        JSONObject(preferences.getString(HISTORY_KEY, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun commit(skills: JSONObject, metadata: JSONObject, history: JSONObject, disabled: JSONArray) {
        check(preferences.edit()
            .putString("skills", skills.toString())
            .putString("skill_metadata_v1", metadata.toString())
            .putString(HISTORY_KEY, history.toString())
            .putString("disabled_skills_v1", disabled.toString())
            .commit()) { "技能状态提交失败；原有技能数据未确认变更" }
    }

    private companion object {
        const val HISTORY_KEY = "skill_community_history_v1"
        const val MAX_HISTORY = 5
    }
}
