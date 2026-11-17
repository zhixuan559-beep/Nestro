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
}
