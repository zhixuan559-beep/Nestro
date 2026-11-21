package app.nestro.ui

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.ModelConfig
import app.nestro.data.ModelProfileCatalogCodec
import app.nestro.data.ModelProfileMetadata
import app.nestro.data.ModelUsageStats
import app.nestro.data.ModelUsageStatsCodec
import app.nestro.model.ModelConfigurationRepository
import app.nestro.model.ModelRequestConfigurationSelector

/**
 * Owns the model-management UI state shared by the model settings and chat
 * surfaces. Persistence and credential operations remain behind
 * ModelConfigurationRepository.
 */
@Stable
internal class ModelManagementUiState(
    private val preferences: SharedPreferences,
    private val repository: ModelConfigurationRepository
) {
    val config: MutableState<ModelConfig> =
        mutableStateOf(ModelConfig("", "", ""))

    val modelProfiles: MutableState<List<ModelProfileMetadata>> =
        mutableStateOf(
            ModelProfileCatalogCodec.decode(
                preferences.getString("model_profiles_v1", "[]") ?: "[]"
            )
        )

    val modelRegistry = mutableStateOf(repository.loadRegistry(modelProfiles.value))

    val activeModelProfileId: MutableState<String> =
        mutableStateOf(preferences.getString("active_model_profile_id", "") ?: "")

    val activeRegistryModelId: MutableState<String> =
        mutableStateOf(preferences.getString("active_model_registry_id", "") ?: "")

    val modelFailoverEnabled: MutableState<Boolean> =
        mutableStateOf(preferences.getBoolean("model_failover_enabled", false))


    /** Restores the active model selection and migrates the legacy single-profile config. */
    fun initialize() {
        var loadedConfig = repository.readActiveConfig()
        var profiles = ModelProfileCatalogCodec.decode(
            preferences.getString("model_profiles_v1", "[]") ?: "[]"
        )
        var activeProfileId = preferences.getString("active_model_profile_id", "") ?: ""
        var activeRegistryId = preferences.getString("active_model_registry_id", "") ?: ""

        if (profiles.isEmpty() && loadedConfig.baseUrl.isNotBlank() && loadedConfig.model.isNotBlank()) {
            val migrated = ModelProfileMetadata("legacy-default", "默认模型", loadedConfig.baseUrl, loadedConfig.model)
            profiles = listOf(migrated)
            if (loadedConfig.apiKey.isNotBlank()) {
                activeProfileId = migrated.id
                repository.writeProfileKey(migrated.id, loadedConfig.apiKey)
                preferences.edit().putString("active_model_profile_id", activeProfileId).apply()
            } else {
                activeProfileId = ""
                preferences.edit().remove("active_model_profile_id").apply()
            }
            preferences.edit().putString("model_profiles_v1", ModelProfileCatalogCodec.encode(profiles)).apply()
        }

        val registry = repository.rebuildLegacyProfiles(profiles)
        val activeRegistryModel = registry.models.firstOrNull { it.id == activeRegistryId }
        val activeRegistryConfig = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry = registry,
            activeModelId = activeRegistryId,
            failoverEnabled = false,
            readKey = repository::readApiKey
        ).firstOrNull()
        val activeRegistryIsUsable = activeRegistryModel != null && activeRegistryConfig != null

        if (activeRegistryIsUsable) {
            activeProfileId = ""
            preferences.edit().remove("active_model_profile_id").apply()
            loadedConfig = activeRegistryConfig!!
            repository.writeActiveConfig(loadedConfig)
        } else {
            if (activeRegistryId.isNotBlank()) {
                activeRegistryId = ""
                preferences.edit().remove("active_model_registry_id").apply()
            }
            val activeMetadata = profiles.firstOrNull { it.id == activeProfileId }
            val activeModelSelectable = registry.selectableModels().any { it.id == "legacy-model-$activeProfileId" }
            if (activeMetadata != null) {
                val activeKey = repository.readProfileKey(activeMetadata.id)
                if (activeKey.isNotBlank() && activeModelSelectable) {
                    activeRegistryId = "legacy-model-" + activeMetadata.id
                    preferences.edit().putString("active_model_registry_id", activeRegistryId).apply()
                    loadedConfig = ModelConfig(activeMetadata.baseUrl, activeMetadata.model, activeKey)
                    repository.writeActiveConfig(loadedConfig)
                } else {
                    activeProfileId = ""
                    loadedConfig = ModelConfig("", "", "")
                    repository.clearActiveConfig()
                    preferences.edit().remove("active_model_profile_id").remove("base_url").remove("model").apply()
                }
            } else {
                activeProfileId = ""
                loadedConfig = ModelConfig("", "", "")
                repository.clearActiveConfig()
                preferences.edit().remove("active_model_profile_id").apply()
            }
        }

        config.value = loadedConfig
        modelProfiles.value = profiles
        modelRegistry.value = registry
        activeModelProfileId.value = activeProfileId
        activeRegistryModelId.value = activeRegistryId
    }

    val modelUsage: MutableState<Map<String, ModelUsageStats>> =
        mutableStateOf(
            ModelUsageStatsCodec.decode(
                preferences.getString("model_usage_v1", "{}") ?: "{}"
            )
        )
}
