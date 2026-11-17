package app.nestro.model

import app.nestro.data.ModelProfileMetadata
import org.json.JSONArray
import org.json.JSONObject

data class ModelProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true
)

data class ModelApiKey(
    val id: String,
    val providerId: String,
    val label: String,
    val enabled: Boolean = true
)

data class ModelDefinition(
    val id: String,
    val providerId: String,
    val name: String,
    val modelCode: String,
    val apiKeyIds: List<String>,
    val enabled: Boolean = true,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val currency: String = "USD"
)

data class ModelRegistry(
    val providers: List<ModelProvider> = emptyList(),
    val apiKeys: List<ModelApiKey> = emptyList(),
    val models: List<ModelDefinition> = emptyList()
) {
    fun selectableModels(): List<ModelDefinition> {
        val providersById = providers.associateBy { it.id }
        val enabledKeys = apiKeys.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
        return models.filter { model ->
            model.enabled &&
                providersById[model.providerId]?.enabled == true &&
                model.apiKeyIds.any { it in enabledKeys }
        }
    }

    fun deleteApiKey(keyId: String): ModelRegistry = copy(
        apiKeys = apiKeys.filterNot { it.id == keyId }
    )

    fun deleteModel(modelId: String): ModelRegistry = copy(
        models = models.filterNot { it.id == modelId }
    )

    fun validate(): String? {
        if (providers.map { it.id }.any(String::isBlank) ||
            providers.map { it.id }.toSet().size != providers.size) return "服务商标识为空或重复"
        if (apiKeys.map { it.id }.any(String::isBlank) ||
            apiKeys.map { it.id }.toSet().size != apiKeys.size) return "API Key 标识为空或重复"
        if (models.map { it.id }.any(String::isBlank) ||
            models.map { it.id }.toSet().size != models.size) return "模型标识为空或重复"
        if (providers.any { it.name.isBlank() || !it.baseUrl.startsWith("https://", true) }) return "服务商名称或 HTTPS 地址无效"
        if (apiKeys.any { key -> providers.none { it.id == key.providerId } }) return "API Key 关联的服务商不存在"
        if (models.any { model -> providers.none { it.id == model.providerId } }) return "模型关联的服务商不存在"
        if (models.any { model -> model.apiKeyIds.any { keyId -> apiKeys.none { it.id == keyId && it.providerId == model.providerId } } }) {
            return "模型引用了不存在或不属于该服务商的 API Key"
        }
        if (models.any { model ->
                listOfNotNull(model.inputPricePerMillion, model.outputPricePerMillion)
                    .any { !it.isFinite() || it < 0.0 }
            }) return "模型价格必须是非负有限数值"
        if (models.any { it.currency.length != 3 }) return "价格币种必须使用三位货币代码"
        return null
    }
}

object ModelRegistryCodec {
    fun encode(registry: ModelRegistry): String {
        require(registry.validate() == null) { registry.validate() ?: "模型目录无效" }
        return JSONObject().apply {
            put("version", 1)
            put("providers", JSONArray().apply {
                registry.providers.forEach { item ->
                    put(JSONObject().put("id", item.id).put("name", item.name)
                        .put("baseUrl", item.baseUrl).put("enabled", item.enabled))
                }
            })
            put("apiKeys", JSONArray().apply {
                registry.apiKeys.forEach { item ->
                    put(JSONObject().put("id", item.id).put("providerId", item.providerId)
                        .put("label", item.label).put("enabled", item.enabled))
                }
            })
            put("models", JSONArray().apply {
                registry.models.forEach { item ->
                    put(JSONObject().put("id", item.id).put("providerId", item.providerId)
                        .put("name", item.name).put("modelCode", item.modelCode)
                        .put("apiKeyIds", JSONArray(item.apiKeyIds))
                        .put("enabled", item.enabled)
                        .put("inputPricePerMillion", item.inputPricePerMillion ?: JSONObject.NULL)
                        .put("outputPricePerMillion", item.outputPricePerMillion ?: JSONObject.NULL)
                        .put("currency", item.currency))
                }
            })
        }.toString()
    }

    fun decode(raw: String): ModelRegistry = runCatching {
        val root = JSONObject(raw)
        require(root.optInt("version") == 1)
        val providers = root.getJSONArray("providers").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ModelProvider(item.getString("id"), item.getString("name"),
                    item.getString("baseUrl"), item.optBoolean("enabled", true))
            }
        }
        val keys = root.getJSONArray("apiKeys").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ModelApiKey(item.getString("id"), item.getString("providerId"),
                    item.getString("label"), item.optBoolean("enabled", true))
            }
        }
        val models = root.getJSONArray("models").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val keyIds = item.getJSONArray("apiKeyIds")
                ModelDefinition(
                    id = item.getString("id"),
                    providerId = item.getString("providerId"),
                    name = item.getString("name"),
                    modelCode = item.getString("modelCode"),
                    apiKeyIds = (0 until keyIds.length()).map { keyIds.getString(it) },
                    enabled = item.optBoolean("enabled", true),
                    inputPricePerMillion = item.optDouble("inputPricePerMillion").takeUnless { it.isNaN() },
                    outputPricePerMillion = item.optDouble("outputPricePerMillion").takeUnless { it.isNaN() },
                    currency = item.optString("currency", "USD")
                )
            }
        }
        ModelRegistry(providers, keys, models).also { require(it.validate() == null) }
    }.getOrDefault(ModelRegistry())

    /**
     * Lossless metadata migration from the legacy one-profile/one-key catalog.
     * Secret values are deliberately not copied; callers move each key through
     * the encrypted secret store using the returned legacy profile ID mapping.
     */
    fun migrateLegacy(profiles: List<ModelProfileMetadata>): ModelRegistry {
        val providers = profiles
            .distinctBy { it.baseUrl.trimEnd('/').lowercase() }
            .map { profile ->
                ModelProvider(
                    id = legacyProviderId(profile.baseUrl),
                    name = profile.baseUrl.trimEnd('/').substringAfter("://").substringBefore('/').ifBlank { "旧服务商" },
                    baseUrl = profile.baseUrl.trimEnd('/')
                )
            }
        val keys = profiles.map { profile ->
            ModelApiKey(
                id = "legacy-key-${profile.id}",
                providerId = legacyProviderId(profile.baseUrl),
                label = profile.name
            )
        }
        val models = profiles.map { profile ->
            ModelDefinition(
                id = "legacy-model-${profile.id}",
                providerId = legacyProviderId(profile.baseUrl),
                name = profile.name,
                modelCode = profile.model,
                apiKeyIds = listOf("legacy-key-${profile.id}")
            )
        }
        return ModelRegistry(providers, keys, models)
            .also { require(it.validate() == null) }
    }

    private fun legacyProviderId(baseUrl: String): String =
        "legacy-provider-${baseUrl.trimEnd('/').lowercase().hashCode().toUInt().toString(16)}"
}
