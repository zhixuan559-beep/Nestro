package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

data class SkillDependency(val name: String, val version: String)

data class SkillBundle(
    val name: String,
    val version: String,
    val description: String,
    val prompt: String,
    val source: String,
    val permissions: List<String>,
    val dependencies: List<SkillDependency>
)

object SkillBundleParser {
    private val allowedPermissions = setOf(
        "network_read", "phone_accessibility", "file_read", "file_write", "mcp_call"
    )

    fun parse(raw: String, sourceLabel: String): SkillBundle {
        require(raw.isNotBlank() && raw.toByteArray(Charsets.UTF_8).size <= 30_000) { "技能文件为空或超过 30 KB" }
        val safeSource = sourceLabel.substringAfterLast('/').take(120).ifBlank { "本地文件" }
        if (!raw.trimStart().startsWith("{")) {
            val name = safeSource.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_ -]"), "").trim().take(40)
            require(name.isNotBlank()) { "无法从文件名生成技能名称" }
            require(raw.trim().length in 1..8000) { "Markdown 技能内容必须为 1 到 8000 个字符" }
            return SkillBundle(name, "1.0.0", "从本地文本文件导入", raw.trim(), safeSource, emptyList(), emptyList())
        }

        val json = JSONObject(raw)
        val name = json.optString("name").trim()
        val version = json.optString("version", "1.0.0").trim()
        val description = json.optString("description", "").trim()
        val prompt = json.optString("prompt").trim()
        val source = json.optString("source", safeSource).take(200)
        require(name.isNotBlank() && name.length <= 40) { "技能名称无效" }
        require(SkillDependencyResolver.isValidVersion(version)) { "技能版本必须使用不超过 40 字符的有效语义版本格式" }
        require(description.length <= 300) { "技能描述过长" }
        require(prompt.length in 1..8000) { "技能指令长度必须为 1 到 8000 个字符" }

        require(!json.has("permissions") || json.optJSONArray("permissions") != null) { "技能权限字段格式无效" }
        require(!json.has("dependencies") || json.optJSONArray("dependencies") != null) { "技能依赖字段格式无效" }
        val permissionArray = json.optJSONArray("permissions") ?: JSONArray()
        require(permissionArray.length() <= 10) { "技能权限数量超出限制" }
        val permissions = (0 until permissionArray.length()).map { permissionArray.getString(it).trim() }.distinct()
        require(permissions.all { it in allowedPermissions }) { "技能声明了不支持的权限" }

        val dependencyArray = json.optJSONArray("dependencies") ?: JSONArray()
        require(dependencyArray.length() <= 10) { "技能依赖数量超出限制" }
        val dependencies = (0 until dependencyArray.length()).map { index ->
            val item = dependencyArray.getJSONObject(index)
            val dependencyName = item.optString("name").trim()
            val dependencyVersion = item.optString("version", "*").trim()
            require(dependencyName.isNotBlank() && dependencyName.length <= 40 && SkillDependencyResolver.isValidConstraint(dependencyVersion)) { "技能依赖名称或版本约束格式无效" }
            SkillDependency(dependencyName, dependencyVersion)
        }
        require(dependencies.map { it.name }.distinct().size == dependencies.size) { "技能依赖不能重复声明" }
        return SkillBundle(name, version, description, prompt, source, permissions, dependencies)
    }

    /** Reconstructs and validates a persisted skill before backup, import, or execution. */
    fun fromStored(name: String, prompt: String, metadataRaw: String?): SkillBundle {
        require(name.isNotBlank() && name.length <= 40) { "技能名称无效" }
        require(prompt.isNotBlank() && prompt.length <= 8_000) { "技能指令为空或过长" }
        val metadata = try {
            JSONObject(metadataRaw ?: "{}")
        } catch (error: Exception) {
            throw IllegalArgumentException("技能元数据损坏", error)
        }
        require(!metadata.has("permissions") || metadata.optJSONArray("permissions") != null) {
            "技能权限元数据格式无效"
        }
        require(!metadata.has("dependencies") || metadata.optJSONArray("dependencies") != null) {
            "技能依赖元数据格式无效"
        }
        val bundle = JSONObject()
            .put("name", name)
            .put("version", metadata.optString("version", "1.0.0").ifBlank { "1.0.0" })
            .put("description", metadata.optString("description", ""))
            .put("prompt", prompt)
            .put("source", metadata.optString("source", "Nestro 本地技能"))
            .put("permissions", metadata.optJSONArray("permissions") ?: JSONArray())
            .put("dependencies", metadata.optJSONArray("dependencies") ?: JSONArray())
        return parse(bundle.toString(), "$name.nestro-skill.json")
    }

    fun metadataJson(bundle: SkillBundle): String =
        JSONObject()
            .put("version", bundle.version)
            .put("description", bundle.description)
            .put("source", bundle.source)
            .put("permissions", JSONArray(bundle.permissions))
            .put("dependencies", JSONArray().apply {
                bundle.dependencies.forEach { put(JSONObject().put("name", it.name).put("version", it.version)) }
            })
            .toString()

    fun exportJson(name: String, prompt: String, metadataRaw: String?): String {
        require(name.isNotBlank() && name.length <= 40) { "技能名称无效" }
        require(prompt.isNotBlank() && prompt.length <= 8_000) { "技能指令为空或过长" }
        val metadata = try {
            JSONObject(metadataRaw ?: "{}")
        } catch (error: Exception) {
            throw IllegalArgumentException("技能元数据损坏，拒绝导出", error)
        }
        val bundle = JSONObject()
            .put("name", name)
            .put("version", metadata.optString("version", "1.0.0"))
            .put("description", metadata.optString("description", ""))
            .put("prompt", prompt)
            .put("source", metadata.optString("source", "Nestro 本地技能"))
            .put("permissions", metadata.optJSONArray("permissions") ?: JSONArray())
            .put("dependencies", metadata.optJSONArray("dependencies") ?: JSONArray())
        parse(bundle.toString(), "$name.nestro-skill.json")
        return bundle.toString(2)
    }
}
