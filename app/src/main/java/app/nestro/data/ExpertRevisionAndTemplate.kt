package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ExpertRevision(val version: String, val prompt: String, val profile: ExpertProfile, val savedAt: Long)

object ExpertRevisionCodec {
    fun decode(raw: String): Map<String, List<ExpertRevision>> = runCatching {
        val root = JSONObject(raw)
        buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val rows = root.optJSONArray(name) ?: continue
                val revisions = (0 until rows.length()).mapNotNull { index ->
                    val item = rows.optJSONObject(index) ?: return@mapNotNull null
                    val profileJson = item.optJSONObject("profile") ?: JSONObject()
                    val profile = ExpertProfileCodec.decode(JSONObject().put(name, profileJson).toString())[name] ?: ExpertProfile()
                    val prompt = item.optString("prompt")
                    if (prompt.isBlank() || prompt.length > 8000) null else ExpertRevision(
                        item.optString("version", profile.version).take(40), prompt, profile, item.optLong("savedAt")
                    )
                }.takeLast(20)
                if (revisions.isNotEmpty()) put(name, revisions)
            }
        }
    }.getOrDefault(emptyMap())

    fun encode(history: Map<String, List<ExpertRevision>>): String = JSONObject().apply {
        history.forEach { (name, revisions) ->
            put(name, JSONArray().apply {
                revisions.takeLast(20).forEach { revision ->
                    val p = revision.profile
                    put(JSONObject().put("version", revision.version).put("prompt", revision.prompt).put("savedAt", revision.savedAt)
                        .put("profile", JSONObject().put("group", p.group).put("avatar", p.avatar).put("enabled", p.enabled)
                            .put("skills", JSONArray(p.skillNames)).put("temperature", p.temperature).put("maxTokens", p.maxTokens)
                            .put("description", p.description).put("version", p.version).put("source", p.source)))
                }
            })
        }
    }.toString()

    fun bumpVersion(version: String): String {
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[+-].*)?$").matchEntire(version) ?: return "1.0.1"
        val major = match.groupValues[1].toIntOrNull() ?: 1
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        return "$major.$minor." + (patch + 1)
    }

    fun diff(previous: String, current: String): List<String> {
        val oldLines = previous.lines()
        val newLines = current.lines()
        val max = maxOf(oldLines.size, newLines.size)
        return (0 until max).mapNotNull { index ->
            val old = oldLines.getOrNull(index)
            val fresh = newLines.getOrNull(index)
            when {
                old == fresh -> null
                old == null -> "+ $fresh"
                fresh == null -> "- $old"
                else -> listOf("- $old", "+ $fresh").joinToString("\n")
            }
        }
    }
}

data class ExpertTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val prompt: String,
    val profile: ExpertProfile
)

object ExpertTemplateCodec {
    fun decode(raw: String): List<ExpertTemplate> = runCatching {
        val rows = JSONArray(raw)
        (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val prompt = item.optString("prompt")
            if (name.isBlank() || name.length > 40 || prompt.isBlank() || prompt.length > 8000) null
            else {
                val profileJson = item.optJSONObject("profile") ?: JSONObject()
                val profile = ExpertProfileCodec.decode(JSONObject().put("profile", profileJson).toString())["profile"] ?: ExpertProfile()
                ExpertTemplate(item.optString("id").ifBlank { UUID.randomUUID().toString() }, name, item.optString("description").take(300), prompt, profile)
            }
        }.take(100)
    }.getOrDefault(emptyList())

    fun encode(templates: List<ExpertTemplate>): String = JSONArray().apply {
        templates.take(100).forEach { template ->
            val p = template.profile
            put(JSONObject().put("id", template.id).put("name", template.name).put("description", template.description).put("prompt", template.prompt)
                .put("profile", JSONObject().put("group", p.group).put("avatar", p.avatar).put("enabled", p.enabled)
                    .put("skills", JSONArray(p.skillNames)).put("temperature", p.temperature).put("maxTokens", p.maxTokens)
                    .put("description", p.description).put("version", p.version).put("source", p.source)))
        }
    }.toString()
}
