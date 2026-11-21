package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONObject

internal object ProjectMemorySettingsStore {
    private const val PREF_KEY = "project_memory_settings_v1"

    fun decode(raw: String): Map<String, Boolean> {
        val json = JSONObject(raw)
        require(json.length() <= 100) { "项目记忆设置数量超过上限" }
        val result = linkedMapOf<String, Boolean>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val projectId = keys.next()
            require(projectId.isNotBlank() && projectId.length <= 120) { "项目记忆设置 ID 无效" }
            result[projectId] = json.opt(projectId) as? Boolean
                ?: error("项目记忆开关必须为布尔值")
        }
        return result
    }

    fun encode(settings: Map<String, Boolean>): String {
        require(settings.size <= 100 && settings.keys.all { it.isNotBlank() && it.length <= 120 }) {
            "项目记忆设置数量或 ID 无效"
        }
        return JSONObject().apply { settings.forEach { (id, enabled) -> put(id, enabled) } }.toString()
    }

    fun isEnabled(preferences: SharedPreferences, projectId: String): Boolean {
        require(projectId.isNotBlank()) { "项目 ID 无效" }
        val settings = decode(preferences.getString(PREF_KEY, "{}") ?: "{}")
        return settings[projectId] ?: (projectId == "default" &&
            preferences.getBoolean("automatic_memory_enabled", false))
    }

    fun setEnabled(preferences: SharedPreferences, projectId: String, enabled: Boolean) {
        require(projectId.isNotBlank()) { "项目 ID 无效" }
        val next = decode(preferences.getString(PREF_KEY, "{}") ?: "{}") + (projectId to enabled)
        val editor = preferences.edit().putString(PREF_KEY, encode(next))
        if (projectId == "default") editor.putBoolean("automatic_memory_enabled", enabled)
        check(editor.commit()) { "项目记忆设置保存失败" }
    }

    fun duplicateProject(preferences: SharedPreferences, sourceId: String, targetId: String) {
        require(sourceId.isNotBlank() && targetId.isNotBlank() && sourceId != targetId) { "项目记忆复制参数无效" }
        val settings = decode(preferences.getString(PREF_KEY, "{}") ?: "{}")
        val enabled = settings[sourceId] ?: (sourceId == "default" &&
            preferences.getBoolean("automatic_memory_enabled", false))
        val next = settings + (targetId to enabled)
        check(preferences.edit().putString(PREF_KEY, encode(next)).commit()) { "项目记忆设置复制失败" }
    }

    fun removeProject(preferences: SharedPreferences, projectId: String) {
        require(projectId.isNotBlank() && projectId != "default") { "不能移除默认项目记忆设置" }
        val settings = decode(preferences.getString(PREF_KEY, "{}") ?: "{}") - projectId
        check(preferences.edit().putString(PREF_KEY, encode(settings)).commit()) { "项目记忆设置清理失败" }
    }
}
