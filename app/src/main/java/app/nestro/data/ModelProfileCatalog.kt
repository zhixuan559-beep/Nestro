package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

data class ModelProfileMetadata(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String
)

object ModelProfileCatalogCodec {
    const val MAX_PROFILES = 20

    fun validate(profile: ModelProfileMetadata): String? {
        if (profile.id.isBlank() || profile.id.length > 80) return "模型配置标识无效"
        if (profile.name.isBlank() || profile.name.length > 40) return "配置名称需为 1 到 40 个字符"
        if (!profile.baseUrl.startsWith("https://", ignoreCase = true) || profile.baseUrl.length > 2048) return "API 地址必须使用 HTTPS，且不超过 2048 个字符"
        if (profile.model.isBlank() || profile.model.length > 200) return "模型名称需为 1 到 200 个字符"
        return null
    }

    fun validateCatalog(profiles: List<ModelProfileMetadata>): String? {
        if (profiles.size > MAX_PROFILES) return "最多保存 $MAX_PROFILES 组模型配置"
        if (profiles.map { it.id }.toSet().size != profiles.size) return "模型配置标识重复"
        if (profiles.map { it.name.trim().lowercase() }.toSet().size != profiles.size) return "模型配置名称不能重复"
        return profiles.firstNotNullOfOrNull(::validate)
    }

    fun encode(profiles: List<ModelProfileMetadata>): String = JSONArray().apply {
        profiles.take(MAX_PROFILES).forEach { profile ->
            put(JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("base_url", profile.baseUrl)
                .put("model", profile.model))
        }
    }.toString()

    fun decode(raw: String): List<ModelProfileMetadata> = runCatching {
        val array = JSONArray(raw)
        require(array.length() <= MAX_PROFILES)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ModelProfileMetadata(
                id = item.getString("id"),
                name = item.getString("name"),
                baseUrl = item.getString("base_url"),
                model = item.getString("model")
            )
        }.also { profiles ->
            require(validateCatalog(profiles) == null)
        }
    }.getOrDefault(emptyList())
}
