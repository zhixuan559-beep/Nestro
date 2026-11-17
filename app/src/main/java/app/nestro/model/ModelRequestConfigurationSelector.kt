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

        fun resolve(model: ModelDefinition): List<ModelConfig> {
            if (!model.enabled) return emptyList()
            val provider = providers[model.providerId]?.takeIf { it.enabled } ?: return emptyList()
            return model.apiKeyIds.mapNotNull { keyId ->
                val key = keys[keyId] ?: return@mapNotNull null
                if (!key.enabled || key.providerId != provider.id) return@mapNotNull null
                val secret = readKey(keyId).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ModelConfig(provider.baseUrl, model.modelCode, secret)
            }
        }

        // Failover is only meaningful after the user has selected a model.
        // An empty or stale selection must never silently choose an arbitrary registry entry.
        if (activeModelId.isBlank()) return emptyList()
        val active = registry.models.firstOrNull { it.id == activeModelId } ?: return emptyList()
        val orderedModels = buildList {
            add(active)
            if (failoverEnabled) {
                registry.models.filterNot { it.id == activeModelId }.forEach(::add)
            }
        }
        return orderedModels.flatMap(::resolve)
            .distinctBy { "${it.baseUrl}|${it.model}|${it.apiKey}" }
    }
}
