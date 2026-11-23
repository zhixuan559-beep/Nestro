package app.nestro.ui

import app.nestro.data.DEFAULT_SKILLS

import org.json.JSONArray
import org.json.JSONObject

internal data class ExpertTeam(val id: String, val name: String, val description: String, val members: List<String>)

internal fun loadSkillMetadata(prefs: android.content.SharedPreferences): Map<String, String> {
    val raw = prefs.getString("skill_metadata_v1", "{}") ?: "{}"
    return runCatching {
        val json = JSONObject(raw)
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key)
            if (key.length <= 40 && value.length <= 4000) result[key] = value
        }
        result
    }.getOrDefault(emptyMap())
}

internal fun skillMetadataToJson(metadata: Map<String, String>): String =
    JSONObject().apply { metadata.forEach { (name, value) -> put(name, value) } }.toString()

internal fun loadDisabledSkills(prefs: android.content.SharedPreferences): Set<String> =
    runCatching {
        val array=JSONArray(prefs.getString("disabled_skills_v1","[]") ?: "[]")
        (0 until array.length()).map { array.getString(it) }.filter { it.length<=40 }.toSet()
    }.getOrDefault(emptySet())

internal fun disabledSkillsToJson(names:Set<String>):String = JSONArray(names.sorted()).toString()

internal fun loadExpertTeams(prefs: android.content.SharedPreferences): List<ExpertTeam> = runCatching {
    val array = JSONArray(prefs.getString("expert_teams_v1", "[]"))
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val members = item.optJSONArray("members") ?: JSONArray()
        ExpertTeam(
            id = item.optString("id"),
            name = item.optString("name"),
            description = item.optString("description"),
            members = (0 until members.length()).map { members.getString(it) }.distinct()
        ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() && it.members.size >= 2 }
    }
}.getOrDefault(emptyList())

internal fun expertTeamsToJson(teams: List<ExpertTeam>): String =
    JSONArray().apply {
        teams.forEach { team ->
            put(JSONObject().put("id", team.id).put("name", team.name).put("description", team.description)
                .put("members", JSONArray(team.members)))
        }
    }.toString()

internal fun loadExperts(prefs: android.content.SharedPreferences): List<String> = runCatching {
    val array = JSONArray(prefs.getString("experts", """["文案官","选题狐","代码佬"]"""))
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(listOf("文案官", "选题狐", "代码佬"))


internal fun loadExpertPrompts(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val raw = prefs.getString("expert_prompts", "{}") ?: "{}"
    val json = JSONObject(raw)
    buildMap { val keys = json.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, json.optString(key)) } }
}.getOrDefault(emptyMap())


internal fun expertPromptsToJson(prompts: Map<String, String>): String {
    val json = JSONObject()
    prompts.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}

internal fun loadSkills(prefs: android.content.SharedPreferences): Map<String, String> = runCatching {
    val json = JSONObject(prefs.getString("skills", "{}") ?: "{}")
    val loaded = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        loaded[name] = json.optString(name)
    }
    DEFAULT_SKILLS + loaded
}.getOrDefault(DEFAULT_SKILLS)

internal fun skillMapToJson(skills: Map<String, String>): String {
    val json = JSONObject()
    skills.forEach { (name, prompt) -> json.put(name, prompt) }
    return json.toString()
}
