package app.nestro.model

import app.nestro.data.ModelConfig
import app.nestro.data.ModelProfileMetadata

/**
 * Pure selection policy for the active model and ordered failover candidates.
 * Credential retrieval is injected so this policy never owns or persists secrets.
 */
internal object ModelRequestConfigurationSelector {
    fun selectableLegacyProfileIds(registry: ModelRegistry): Set<String> =
        registry.selectableModels()
            .mapNotNull { model ->
                model.id.removePrefix("legacy-model-")
                    .takeIf { model.id.startsWith("legacy-model-") }
            }
            .toSet()

    fun buildRequestConfigs(
        registry: ModelRegistry,
        profiles: List<ModelProfileMetadata>,
        activeProfileId: String,
        activeConfig: ModelConfig,
        failoverEnabled: Boolean,
        readKey: (profileId: String) -> String
    ): List<ModelConfig> {
        val selectableIds = selectableLegacyProfileIds(registry)
        return buildList {
            if (
                activeProfileId in selectableIds &&
                activeConfig.baseUrl.isNotBlank() &&
                activeConfig.model.isNotBlank() &&
                activeConfig.apiKey.isNotBlank()
            ) {
                add(activeConfig)
            }
            if (failoverEnabled) {
                profiles.filter { it.id != activeProfileId && it.id in selectableIds }
                    .forEach { profile ->
                        val key = readKey(profile.id)
                        if (key.isNotBlank()) add(ModelConfig(profile.baseUrl, profile.model, key))
                    }
            }
        }.distinctBy { "${it.baseUrl}|${it.model}" }
    }

    /**
     * Builds request candidates from the independent provider/model/API-key registry.
     * Secrets are resolved by key ID at call time and are never stored in registry metadata.
     */
    fun buildRegistryRequestConfigs(
        registry: ModelRegistry,
        activeModelId: String,
        failoverEnabled: Boolean,
        readKey: (keyId: String) -> String
    ): List<ModelConfig> {
        val providers = registry.providers.associateBy { it.id }
        val keys = registry.apiKeys.associateBy { it.id }

        fun resolve(model: ModelDefinition): ModelConfig? {
            if (!model.enabled) return null
            val provider = providers[model.providerId]?.takeIf { it.enabled } ?: return null
            val secret = model.apiKeyIds.asSequence()
                .mapNotNull { keyId ->
                    val key = keys[keyId] ?: return@mapNotNull null
                    if (!key.enabled || key.providerId != provider.id) return@mapNotNull null
                    readKey(keyId).takeIf { it.isNotBlank() }
                }
                .firstOrNull() ?: return null
            return ModelConfig(provider.baseUrl, model.modelCode, secret)
        }

        val candidates = registry.models
        return buildList {
            candidates.firstOrNull { it.id == activeModelId }?.let { resolve(it)?.let(::add) }
            if (failoverEnabled) {
                candidates.asSequence()
                    .filterNot { it.id == activeModelId }
                    .forEach { model -> resolve(model)?.let(::add) }
            }
        }.distinctBy { "${it.baseUrl}|${it.model}" }
    }
}
