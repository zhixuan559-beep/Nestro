package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

data class ExpertProfile(
    val group: String = "默认",
    val avatar: String = "✦",
    val enabled: Boolean = true,
    val skillNames: List<String> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val description: String = "",
    val version: String = "1.0.0",
    val source: String = "Nost 本地专家"
)

object ExpertProfileCodec {
    fun decode(raw: String): Map<String, ExpertProfile> = runCatching {
        val root = JSONObject(raw)
        buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val item = root.optJSONObject(name) ?: continue
                val skills = item.optJSONArray("skills") ?: JSONArray()
                val temperature = if (item.has("temperature") && !item.isNull("temperature")) item.optDouble("temperature") else null
                val maxTokens = if (item.has("maxTokens") && !item.isNull("maxTokens")) item.optInt("maxTokens") else null
                put(name, ExpertProfile(
                    group = item.optString("group", "默认").take(30).ifBlank { "默认" },
                    avatar = item.optString("avatar", "✦").take(8).ifBlank { "✦" },
                    enabled = item.optBoolean("enabled", true),
                    skillNames = (0 until skills.length()).map { skills.optString(it) }.filter { it.isNotBlank() }.distinct().take(20),
                    temperature = temperature?.takeIf { it.isFinite() && it in 0.0..2.0 },
                    maxTokens = maxTokens?.takeIf { it in 1..32768 },
                    description = item.optString("description").take(300),
                    version = item.optString("version", "1.0.0").take(40),
                    source = item.optString("source", "Nestro 本地专家").take(200)
                ))
            }
        }
    }.getOrDefault(emptyMap())

    fun encode(profiles: Map<String, ExpertProfile>): String = JSONObject().apply {
        profiles.forEach { (name, profile) ->
            put(name, JSONObject()
                .put("group", profile.group)
                .put("avatar", profile.avatar)
                .put("enabled", profile.enabled)
                .put("skills", JSONArray(profile.skillNames.distinct().take(20)))
                .put("temperature", profile.temperature)
                .put("maxTokens", profile.maxTokens)
                .put("description", profile.description)
                .put("version", profile.version)
                .put("source", profile.source))
        }
    }.toString()

    fun validate(profile: ExpertProfile, availableSkills: Set<String>): String? {
        if (profile.group.isBlank() || profile.group.length > 30) return "专家分组必须为 1 到 30 个字符"
        if (!profile.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+([+-][A-Za-z0-9.-]+)?")) || profile.source.length > 200) return "专家版本或来源信息无效"
        if (profile.avatar.isBlank() || profile.avatar.length > 8) return "头像标识无效"
        if (profile.skillNames.size > 20 || profile.skillNames.any { it !in availableSkills }) return "挂载技能不存在，请重新选择"
        if (profile.temperature != null && (!profile.temperature.isFinite() || profile.temperature !in 0.0..2.0)) return "Temperature 必须在 0 到 2 之间"
        if (profile.maxTokens != null && profile.maxTokens !in 1..32768) return "最大输出 Token 必须在 1 到 32768 之间"
        if (profile.description.length > 300) return "专家描述不能超过 300 个字符"
        return null
    }

    fun skillContext(
        profile: ExpertProfile?,
        skills: Map<String, String>,
        disabledSkills: Set<String>,
        skillMetadata: Map<String, String> = emptyMap()
    ): String {
        val activeProfile = profile?.takeIf { it.enabled } ?: return ""
        val runnableSkills = SkillDependencyResolver.filterSatisfiedSkills(skills, skillMetadata, disabledSkills)
        return activeProfile.skillNames.mapNotNull { name ->
            runnableSkills[name]?.takeIf { it.isNotBlank() }?.let { "专家挂载技能「$name」：\n$it" }
        }.joinToString("\n\n")
    }
}
