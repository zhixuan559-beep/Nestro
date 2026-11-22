package app.nestro.model

import app.nestro.data.ModelProfileMetadata
import org.json.JSONArray
import java.net.URI
import org.json.JSONObject

data class ModelProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val presetId: String = "",
    val protocol: ModelApiProtocol = ModelApiProtocol.OPENAI_CHAT_COMPLETIONS
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
    val currency: String = "USD",
    val supportsReasoningEffort: Boolean = false,
    val contextWindowTokens: Int? = null
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

    fun upsertProvider(provider: ModelProvider): ModelRegistry =
        copy(providers = providers.filterNot { it.id == provider.id } + provider)

    fun deleteProvider(providerId: String): ModelRegistry {
        val removedKeyIds = apiKeys.filter { it.providerId == providerId }.mapTo(mutableSetOf()) { it.id }
        return copy(
            providers = providers.filterNot { it.id == providerId },
            apiKeys = apiKeys.filterNot { it.providerId == providerId },
            models = models.filterNot { it.providerId == providerId || it.apiKeyIds.any { keyId -> keyId in removedKeyIds } }
        )
    }

    fun upsertApiKey(key: ModelApiKey): ModelRegistry {
        require(providers.any { it.id == key.providerId }) { "API Key 对应的服务商不存在" }
        val existing = apiKeys.firstOrNull { it.id == key.id }
        require(existing == null || existing.providerId == key.providerId) {
            "API Key 不能直接转移到其他服务商"
        }
        return copy(apiKeys = apiKeys.filterNot { it.id == key.id } + key)
    }

    fun deleteApiKey(keyId: String): ModelRegistry = copy(
        apiKeys = apiKeys.filterNot { it.id == keyId },
        models = models.map { model ->
            model.copy(apiKeyIds = model.apiKeyIds.filterNot { it == keyId })
        }
    )

    fun upsertModel(model: ModelDefinition): ModelRegistry {
        require(providers.any { it.id == model.providerId }) { "模型对应的服务商不存在" }
        require(model.apiKeyIds.all { keyId -> apiKeys.any { it.id == keyId && it.providerId == model.providerId } }) {
            "模型只能关联同一服务商下已存在的 API Key"
        }
        return copy(models = models.filterNot { it.id == model.id } + model)
    }

    fun attachApiKey(modelId: String, keyId: String): ModelRegistry {
        val model = models.firstOrNull { it.id == modelId } ?: error("模型不存在")
        val key = apiKeys.firstOrNull { it.id == keyId } ?: error("API Key 不存在")
        require(model.providerId == key.providerId) { "模型与 API Key 必须属于同一服务商" }
        return copy(models = models.map {
            if (it.id == modelId) it.copy(apiKeyIds = (it.apiKeyIds + keyId).distinct()) else it
        })
    }

    fun detachApiKey(modelId: String, keyId: String): ModelRegistry {
        require(models.any { it.id == modelId }) { "模型不存在" }
        return copy(models = models.map {
            if (it.id == modelId) it.copy(apiKeyIds = it.apiKeyIds.filterNot { id -> id == keyId }) else it
        })
    }

    fun upsertLegacyProfile(
        profile: ModelProfileMetadata,
        keyAvailable: Boolean,
        enableKey: Boolean = false
    ): ModelRegistry {
        val providerId = ModelRegistryCodec.legacyProviderId(profile.baseUrl)
        val keyId = "legacy-key-${profile.id}"
        val modelId = "legacy-model-${profile.id}"
        val previousProvider = providers.firstOrNull { it.id == providerId }
        val provider = ModelProvider(
            providerId,
            profile.baseUrl.trimEnd('/').substringAfter("://").substringBefore('/').ifBlank { "旧服务商" },
            profile.baseUrl.trimEnd('/'),
            enabled = previousProvider?.enabled ?: true
        )
        val nextProviders = (providers.filterNot { it.id == providerId } + provider)
        val previousKey = apiKeys.firstOrNull { it.id == keyId }
        val nextKeys = if (keyAvailable) {
            apiKeys.filterNot { it.id == keyId } + ModelApiKey(
                keyId, providerId, profile.name,
                enabled = if (enableKey) true else previousKey?.enabled ?: true
            )
        } else apiKeys.filterNot { it.id == keyId }
        val previousModel = models.firstOrNull { it.id == modelId }
        val retainedKeyIds = previousModel?.apiKeyIds.orEmpty()
            .filterNot { it == keyId }
            .filter { existingKeyId -> apiKeys.any { it.id == existingKeyId && it.providerId == providerId } }
        val nextModels = models.filterNot { it.id == modelId } + ModelDefinition(
            id = modelId,
            providerId = providerId,
            name = profile.name,
            modelCode = profile.model,
            apiKeyIds = (retainedKeyIds + if (keyAvailable) listOf(keyId) else emptyList()).distinct(),
            enabled = previousModel?.enabled ?: true,
            inputPricePerMillion = previousModel?.inputPricePerMillion,
            outputPricePerMillion = previousModel?.outputPricePerMillion,
            currency = previousModel?.currency ?: "USD",
            supportsReasoningEffort = previousModel?.supportsReasoningEffort ?: false,
            contextWindowTokens = previousModel?.contextWindowTokens
        )
        val usedProviderIds = (nextKeys.map { it.providerId } + nextModels.map { it.providerId }).toSet()
        return copy(
            providers = nextProviders.filter { it.id in usedProviderIds },
            apiKeys = nextKeys,
            models = nextModels
        )
    }

    fun deleteLegacyProfile(profileId: String): ModelRegistry {
        val withoutKey = deleteApiKey("legacy-key-$profileId")
        val nextModels = withoutKey.models.filterNot { it.id == "legacy-model-$profileId" }
        val usedProviderIds = (withoutKey.apiKeys.map { it.providerId } + nextModels.map { it.providerId }).toSet()
        return withoutKey.copy(
            models = nextModels,
            providers = withoutKey.providers.filter { it.id in usedProviderIds }
        )
    }

    fun deleteModel(modelId: String): ModelRegistry = copy(
        models = models.filterNot { it.id == modelId }
    )

    fun reconcileLegacyProfiles(
        profiles: List<ModelProfileMetadata>,
        hasSecret: (profileId: String) -> Boolean
    ): ModelRegistry {
        val migrated = ModelRegistryCodec.migrateLegacy(profiles)
        val legacyKeys = migrated.apiKeys.mapNotNull { key ->
            val profileId = key.id.removePrefix("legacy-key-")
            val old = apiKeys.firstOrNull { it.id == key.id }
            if (hasSecret(profileId)) key.copy(enabled = old?.enabled ?: true) else null
        }
        val legacyProviders = migrated.providers.map { provider ->
            provider.copy(enabled = providers.firstOrNull { it.id == provider.id }?.enabled ?: provider.enabled)
        }
        val legacyModels = migrated.models.map { model ->
            val profileId = model.id.removePrefix("legacy-model-")
            val old = models.firstOrNull { it.id == model.id }
            model.copy(
                apiKeyIds = if (hasSecret(profileId)) listOf("legacy-key-" + profileId) else emptyList(),
                enabled = old?.enabled ?: model.enabled,
                inputPricePerMillion = old?.inputPricePerMillion,
                outputPricePerMillion = old?.outputPricePerMillion,
                currency = old?.currency ?: model.currency,
                supportsReasoningEffort = old?.supportsReasoningEffort ?: model.supportsReasoningEffort,
                contextWindowTokens = old?.contextWindowTokens ?: model.contextWindowTokens
            )
        }
        return ModelRegistry(
            providers = providers.filterNot { it.id.startsWith("legacy-provider-") } + legacyProviders,
            apiKeys = apiKeys.filterNot { it.id.startsWith("legacy-key-") } + legacyKeys,
            models = models.filterNot { it.id.startsWith("legacy-model-") } + legacyModels
        ).also { require(it.validate() == null) { it.validate() ?: "模型目录无效" } }
    }

    fun validate(): String? {
        if (providers.map { it.id }.any(String::isBlank) ||
            providers.map { it.id }.toSet().size != providers.size) return "服务商标识为空或重复"
        if (apiKeys.map { it.id }.any(String::isBlank) ||
            apiKeys.map { it.id }.toSet().size != apiKeys.size) return "API Key 标识为空或重复"
        if (models.map { it.id }.any(String::isBlank) ||
            models.map { it.id }.toSet().size != models.size) return "模型标识为空或重复"
        if (providers.any { provider ->
                provider.name.isBlank() || runCatching {
                    val uri = URI(provider.baseUrl)
                    !uri.scheme.equals("https", ignoreCase = true) ||
                        uri.host.isNullOrBlank() ||
                        uri.userInfo != null ||
                        uri.query != null ||
                        uri.fragment != null
                }.getOrDefault(true)
            }) return "服务商名称或 HTTPS 地址无效"
        if (apiKeys.any { key -> providers.none { it.id == key.providerId } }) return "API Key 关联的服务商不存在"
        if (models.any { model -> providers.none { it.id == model.providerId } }) return "模型关联的服务商不存在"
        if (models.any { model -> model.apiKeyIds.any { keyId -> apiKeys.none { it.id == keyId && it.providerId == model.providerId } } }) {
            return "模型引用了不存在或不属于该服务商的 API Key"
        }
        if (models.any { model ->
                model.contextWindowTokens != null && model.contextWindowTokens !in 1_024..2_000_000
            }) return "模型上下文窗口需为 1024 到 2000000 tokens，或留空使用保守默认值"
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
                        .put("baseUrl", item.baseUrl).put("enabled", item.enabled)
                        .put("presetId", item.presetId).put("protocol", item.protocol.name))
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
                        .put("currency", item.currency)
                        .put("supportsReasoningEffort", item.supportsReasoningEffort)
                        .put("contextWindowTokens", item.contextWindowTokens ?: JSONObject.NULL))
                }
            })
        }.toString()
    }

    fun decodeOrNull(raw: String): ModelRegistry? = runCatching {
        val root = JSONObject(raw)
        require(root.optInt("version") == 1)
        val providers = root.getJSONArray("providers").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ModelProvider(
                    item.getString("id"), item.getString("name"),
                    item.getString("baseUrl"), item.optBoolean("enabled", true),
                    item.optString("presetId", ""),
                    runCatching { ModelApiProtocol.valueOf(item.optString("protocol", ModelApiProtocol.OPENAI_CHAT_COMPLETIONS.name)) }
                        .getOrDefault(ModelApiProtocol.OPENAI_CHAT_COMPLETIONS)
                )
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
                    inputPricePerMillion = if (item.isNull("inputPricePerMillion")) null else item.optDouble("inputPricePerMillion"),
                    outputPricePerMillion = if (item.isNull("outputPricePerMillion")) null else item.optDouble("outputPricePerMillion"),
                    currency = item.optString("currency", "USD"),
                    supportsReasoningEffort = item.optBoolean("supportsReasoningEffort", false),
                    contextWindowTokens = if (item.isNull("contextWindowTokens")) null else item.optInt("contextWindowTokens")
                )
            }
        }
        ModelRegistry(providers, keys, models).also { require(it.validate() == null) }
    }.getOrNull()

    /** Backward-compatible tolerant decoder for non-destructive read-only callers. */
    fun decode(raw: String): ModelRegistry = decodeOrNull(raw) ?: ModelRegistry()

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

    internal fun legacyProviderId(baseUrl: String): String =
        "legacy-provider-${baseUrl.trimEnd('/').lowercase().hashCode().toUInt().toString(16)}"
}
