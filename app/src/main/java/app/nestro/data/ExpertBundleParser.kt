package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

data class ExpertBundle(
    val name: String,
    val version: String,
    val description: String,
    val prompt: String,
    val source: String,
    val group: String = "默认",
    val avatar: String = "✦",
    val enabled: Boolean = true,
    val skillNames: List<String> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val mascot: String = "capybara"
) {
    fun profile(): ExpertProfile = ExpertProfile(group, avatar, enabled, skillNames, temperature, maxTokens, description, version, source, mascot)
}

object ExpertBundleParser {
    fun parse(raw: String, sourceLabel: String): ExpertBundle {
        require(raw.isNotBlank() && raw.length <= 20_000) { "专家文件为空或超过 20 KB" }
        val sourceName = sourceLabel.substringAfterLast('/').take(120).ifBlank { "本地文件" }
        val json = JSONObject(raw)
        val name = json.optString("name").trim()
        val version = json.optString("version", "1.0.0").trim()
        val description = json.optString("description", "").trim()
        val prompt = json.optString("prompt").trim()
        val source = json.optString("source", sourceName).trim().take(200)
        val group = json.optString("group", "默认").trim().ifBlank { "默认" }
        val avatar = json.optString("avatar", "✦").trim().ifBlank { "✦" }
        val enabled = json.optBoolean("enabled", true)
        val skillArray = json.optJSONArray("skills") ?: JSONArray()
        require(skillArray.length() <= 20) { "专家挂载技能不能超过 20 个" }
        val skillNames = (0 until skillArray.length()).map { skillArray.getString(it).trim() }.distinct()
        val temperature = if (json.has("temperature") && !json.isNull("temperature")) json.optDouble("temperature") else null
        val maxTokens = if (json.has("max_tokens") && !json.isNull("max_tokens")) json.optInt("max_tokens") else null
        val mascot = json.optString("mascot", "capybara").trim().take(20).takeIf { it.isBlank() || it == "capybara" } ?: "capybara"
        require(name.isNotBlank() && name.length <= 40) { "专家名称无效" }
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+([+-][A-Za-z0-9.-]+)?"))) { "专家版本必须使用语义版本格式" }
        require(description.length <= 300) { "专家描述不能超过 300 个字符" }
        require(prompt.isNotBlank() && prompt.length <= 8_000) { "专家角色指令为空或过长" }
        require(group.length <= 30 && avatar.length <= 8) { "专家分组或头像标识无效" }
        require(skillNames.all { it.isNotBlank() && it.length <= 40 }) { "专家挂载技能名称无效" }
        require(temperature == null || (temperature.isFinite() && temperature in 0.0..2.0)) { "Temperature 必须在 0 到 2 之间" }
        require(maxTokens == null || maxTokens in 1..32768) { "最大输出 Token 必须在 1 到 32768 之间" }
        return ExpertBundle(name, version, description, prompt, source, group, avatar, enabled, skillNames, temperature, maxTokens, mascot)
    }

    fun exportJson(name: String, prompt: String, description: String = "", profile: ExpertProfile = ExpertProfile()): String {
        require(name.isNotBlank() && name.length <= 40) { "专家名称无效" }
        require(prompt.isNotBlank() && prompt.length <= 8_000) { "专家角色指令为空或过长" }
        require(description.length <= 300) { "专家描述不能超过 300 个字符" }
        return JSONObject()
            .put("name", name)
            .put("version", profile.version)
            .put("description", description.ifBlank { profile.description })
            .put("prompt", prompt)
            .put("source", profile.source.ifBlank { "Nestro 本地专家" })
            .put("group", profile.group)
            .put("avatar", profile.avatar)
            .put("mascot", profile.mascot)
            .put("enabled", profile.enabled)
            .put("skills", JSONArray(profile.skillNames))
            .put("temperature", profile.temperature)
            .put("max_tokens", profile.maxTokens)
            .toString(2)
    }
}
