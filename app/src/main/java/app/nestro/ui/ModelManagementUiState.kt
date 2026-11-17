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

/**
 * Owns the model-management UI state shared by the model settings and chat
 * surfaces. Persistence and credential operations remain behind
 * ModelConfigurationRepository.
 */
@Stable
internal class ModelManagementUiState(
    preferences: SharedPreferences,
    repository: ModelConfigurationRepository
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

    val modelFailoverEnabled: MutableState<Boolean> =
        mutableStateOf(preferences.getBoolean("model_failover_enabled", false))

    val modelUsage: MutableState<ModelUsageStats> =
        mutableStateOf(
            ModelUsageStatsCodec.decode(
                preferences.getString("model_usage_v1", "{}") ?: "{}"
            )
        )
}
