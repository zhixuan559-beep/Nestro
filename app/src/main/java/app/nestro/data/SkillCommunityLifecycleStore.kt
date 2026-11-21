package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Atomic local lifecycle for verified community skills.
 * Previous versions are retained for rollback; no permission is granted here.
 */
class SkillCommunityLifecycleStore(private val preferences: SharedPreferences) {
    fun install(bundle: SkillBundle) {
        require(bundle.source == "已验证签名社区目录") { "仅允许安装经过签名目录验证的社区技能" }
        validateDependencies(bundle)
        val skills = JSONObject(preferences.getString("skills", "{}") ?: "{}")
        val metadata = JSONObject(preferences.getString("skill_metadata_v1", "{}") ?: "{}")
        val history = JSONObject(preferences.getString(HISTORY_KEY, "{}") ?: "{}")
        val disabledBefore = isDisabled(bundle.name)
        val existingPrompt = skills.optString(bundle.name, null)
        val existingMetaRaw = metadata.optString(bundle.name, null)
        val existingMeta = existingMetaRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (existingPrompt != null && existingMeta?.optString("source") != "已验证签名社区目录") {
            error("同名本地技能已存在；为防止覆盖，已拒绝安装")
        }
        if (existingPrompt != null) {
            val installedVersion = existingMeta?.optString("version").orEmpty()
            require(installedVersion.isNotBlank() && SkillCommunityClient.isNewerVersion(bundle.version, installedVersion)) {
                "仅允许安装高于当前社区版本的更新"
            }
        }
        val snapshots = history.optJSONArray(bundle.name) ?: JSONArray()
        snapshots.put(JSONObject()
            .put("hadSkill", existingPrompt != null)
            .put("prompt", existingPrompt ?: JSONObject.NULL)
            .put("metadata", existingMetaRaw ?: JSONObject.NULL)
            .put("wasDisabled", disabledBefore)
            .put("savedAt", System.currentTimeMillis()))
        while (snapshots.length() > MAX_HISTORY) snapshots.remove(0)
        history.put(bundle.name, snapshots)
        skills.put(bundle.name, bundle.prompt)
        metadata.put(bundle.name, SkillBundleParser.metadataJson(bundle))
        val disabledAfter = if (existingPrompt == null) setDisabled(bundle.name, true) else disabledSkills()
        commit(skills, metadata, history, disabledAfter)
    }

    fun uninstall(name: String) {
        require(name.isNotBlank() && name.length <= 40)
        val skills = JSONObject(preferences.getString("skills", "{}") ?: "{}")
        val metadata = JSONObject(preferences.getString("skill_metadata_v1", "{}") ?: "{}")
        val currentMeta = runCatching { JSONObject(metadata.optString(name, "{}")) }.getOrDefault(JSONObject())
        require(currentMeta.optString("source") == "已验证签名社区目录") { "仅可从社区管理器卸载社区技能" }
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
        val history = historyJson()
        val snapshots = history.optJSONArray(name) ?: error("没有可回退的历史版本")
        require(snapshots.length() > 0) { "没有可回退的历史版本" }
        val previous = snapshots.getJSONObject(snapshots.length() - 1)
        snapshots.remove(snapshots.length() - 1)
        val disabled = setDisabled(name, previous.optBoolean("wasDisabled", true))
        if (previous.optBoolean("hadSkill")) {
            skills.put(name, previous.getString("prompt"))
            metadata.put(name, previous.getString("metadata"))
        } else {
            skills.remove(name)
            metadata.remove(name)
            disabled.remove(name)
        }
        if (snapshots.length() == 0) history.remove(name) else history.put(name, snapshots)
        commit(skills, metadata, history, disabled)
    }

    fun canRollback(name: String): Boolean = (historyJson().optJSONArray(name)?.length() ?: 0) > 0

    private fun validateDependencies(bundle: SkillBundle) {
        val metadata = JSONObject(preferences.getString("skill_metadata_v1", "{}") ?: "{}")
        bundle.dependencies.forEach { dependency ->
            require(dependency.name != bundle.name) { "技能不能依赖自身" }
            val raw = metadata.optString(dependency.name, null)
                ?: error("缺少技能依赖：${dependency.name}")
            val installed = runCatching { JSONObject(raw).optString("version") }.getOrDefault("")
            require(installed.isNotBlank()) { "依赖技能版本无效：${dependency.name}" }
            require(versionMatches(installed, dependency.version)) {
                "依赖版本不匹配：${dependency.name} 需要 ${dependency.version}，本机为 $installed"
            }
        }
    }

    private fun versionMatches(installed: String, requirement: String): Boolean {
        if (requirement == "*") return true
        val operator = listOf(">=", "<=", ">", "<", "^", "~").firstOrNull { requirement.startsWith(it) }
        val requested = if (operator == null) requirement else requirement.removePrefix(operator)
        if (operator == null) return installed == requested
        fun parts(value: String): List<Long> {
            val core = value.substringBefore('-').substringBefore('+')
            val values = core.split('.')
            require(values.size in 2..3 && values.all { it.matches(Regex("[0-9]{1,18}")) }) { "技能版本格式无效：$value" }
            return (values.map { it.toLong() } + listOf(0L, 0L, 0L)).take(3)
        }
        val actual = parts(installed)
        val expected = parts(requested)
        val comparison = compareValuesBy(actual, expected, { it[0] }, { it[1] }, { it[2] })
        return when (operator) {
            ">=" -> comparison >= 0
            "<=" -> comparison <= 0
            ">" -> comparison > 0
            "<" -> comparison < 0
            "^" -> comparison >= 0 && actual[0] == expected[0]
            "~" -> comparison >= 0 && actual[0] == expected[0] && actual[1] == expected[1]
            else -> false
        }
    }

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

    private fun disabledSkills(): JSONArray = runCatching {
        JSONArray(preferences.getString("disabled_skills_v1", "[]") ?: "[]")
    }.getOrDefault(JSONArray())

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
