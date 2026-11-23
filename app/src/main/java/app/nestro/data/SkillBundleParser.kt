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
    val dependencies: List<SkillDependency>,
    val signerFingerprint: String = ""
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
        require(runCatching { SkillSemVer.compare(version, version) }.isSuccess) { "技能版本必须使用严格 SemVer 2.0 格式" }
        require(description.length <= 300) { "技能描述过长" }
        require(prompt.length in 1..8000) { "技能指令长度必须为 1 到 8000 个字符" }

        require(!json.has("permissions") || json.optJSONArray("permissions") != null) { "技能权限字段格式无效" }
        require(!json.has("dependencies") || json.optJSONArray("dependencies") != null) { "技能依赖字段格式无效" }
        val permissionArray = json.optJSONArray("permissions") ?: JSONArray()
        require(permissionArray.length() <= 10) { "技能权限数量超出限制" }
        val permissions = (0 until permissionArray.length()).map { index ->
            val value = permissionArray.opt(index)
            require(value is String) { "技能权限项必须为字符串" }
            value.trim()
        }.distinct()
        require(permissions.all { it in allowedPermissions }) { "技能声明了不支持的权限" }

        val dependencyArray = json.optJSONArray("dependencies") ?: JSONArray()
        require(dependencyArray.length() <= 10) { "技能依赖数量超出限制" }
        val dependencies = (0 until dependencyArray.length()).map { index ->
            val item = dependencyArray.optJSONObject(index)
                ?: throw IllegalArgumentException("技能依赖项格式无效")
            val dependencyName = item.optString("name").trim()
            val dependencyVersion = item.optString("version", "*").trim()
            require(dependencyName.isNotBlank() && dependencyName.length <= 80 && dependencyVersion.length <= 40) { "技能依赖格式无效" }
            val alternatives = dependencyVersion.split("||")
            require(dependencyVersion == "*" || (alternatives.isNotEmpty() && alternatives.all { alternative -> alternative.isNotBlank() && alternative.trim().split(Regex("[ ,]+")).filter { it.isNotBlank() }.all { term -> runCatching { SkillSemVer.matches("1.0.0", term) }.isSuccess } })) { "技能依赖版本必须使用有效 SemVer 2.0 范围或 *" }
            SkillDependency(dependencyName, dependencyVersion)
        }
        require(dependencies.map { it.name }.distinct().size == dependencies.size) { "技能依赖不能重复" }
        require(dependencies.none { it.name == name }) { "技能不能依赖自身" }
        val signerFingerprint = json.optString("signerFingerprint", "").trim().lowercase()
        require(signerFingerprint.isBlank() || signerFingerprint.matches(Regex("[a-f0-9]{64}"))) {
            "技能签名者指纹无效"
        }
        return SkillBundle(name, version, description, prompt, source, permissions, dependencies, signerFingerprint)
    }

    /** Reconstructs persisted skill metadata through the same strict parser used for imports. */
    fun fromStored(name: String, prompt: String, metadataRaw: String?): SkillBundle {
        require(name.isNotBlank() && name.length <= 40) { "技能名称无效" }
        require(prompt.isNotBlank() && prompt.length <= 8_000) { "技能指令为空或过长" }
        val metadata = try {
            JSONObject(metadataRaw?.takeIf { it.isNotBlank() } ?: "{}")
        } catch (error: Exception) {
            throw IllegalArgumentException("技能元数据损坏，拒绝导出", error)
        }
        require(!metadata.has("permissions") || metadata.optJSONArray("permissions") != null) {
            "技能权限元数据格式无效"
        }
        require(!metadata.has("dependencies") || metadata.optJSONArray("dependencies") != null) {
            "技能依赖元数据格式无效"
        }
        val signerFingerprint = metadata.optString("signerFingerprint", "").trim().lowercase()
        require(signerFingerprint.isBlank() || signerFingerprint.matches(Regex("[a-f0-9]{64}"))) {
            "技能签名者指纹无效"
        }
        val storedBundle = JSONObject()
            .put("name", name)
            .put("version", metadata.optString("version", "1.0.0").ifBlank { "1.0.0" })
            .put("description", metadata.optString("description", ""))
            .put("prompt", prompt)
            .put("source", metadata.optString("source", "Nestro 本地技能"))
            .put("permissions", metadata.optJSONArray("permissions") ?: JSONArray())
            .put("dependencies", metadata.optJSONArray("dependencies") ?: JSONArray())
            .put("signerFingerprint", signerFingerprint)
        return try {
            parse(storedBundle.toString(), "$name.nestro-skill.json")
        } catch (error: Exception) {
            throw IllegalArgumentException("技能元数据无效，拒绝导出", error)
        }
    }

    fun metadataJson(bundle: SkillBundle): String =
        JSONObject()
            .put("version", bundle.version)
            .put("description", bundle.description)
            .put("source", bundle.source)
            .put("signerFingerprint", bundle.signerFingerprint)
            .put("permissions", JSONArray(bundle.permissions))
            .put("dependencies", JSONArray().apply {
                bundle.dependencies.forEach { put(JSONObject().put("name", it.name).put("version", it.version)) }
            })
            .toString()

    fun exportJson(name: String, prompt: String, metadataRaw: String?): String {
        val bundle = fromStored(name, prompt, metadataRaw)
        return JSONObject()
            .put("name", bundle.name)
            .put("version", bundle.version)
            .put("description", bundle.description)
            .put("prompt", bundle.prompt)
            .put("source", bundle.source)
            .put("signerFingerprint", bundle.signerFingerprint)
            .put("permissions", JSONArray(bundle.permissions))
            .put("dependencies", JSONArray().apply {
                bundle.dependencies.forEach { put(JSONObject().put("name", it.name).put("version", it.version)) }
            })
            .toString(2)
    }
}
