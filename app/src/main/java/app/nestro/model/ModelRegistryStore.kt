package app.nestro.model

import android.content.Context
import app.nestro.data.ModelProfileMetadata

class ModelRegistryStore(context: Context) {
    private val preferences = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)

    fun load(
        legacyProfiles: List<ModelProfileMetadata>,
        hasSecret: (profileId: String) -> Boolean
    ): ModelRegistry {
        val saved = preferences.getString(KEY, null)
        if (!saved.isNullOrBlank()) {
            val decoded = ModelRegistryCodec.decode(saved)
            if (decoded.validate() == null && (decoded.providers.isNotEmpty() || legacyProfiles.isEmpty())) {
                return decoded
            }
        }

        val migrated = ModelRegistryCodec.migrateLegacy(legacyProfiles)
        val migratedKeys = migrated.apiKeys.map { key ->
            val profileId = key.id.removePrefix("legacy-key-")
            key.copy(enabled = hasSecret(profileId))
        }
        val migratedModels = migrated.models.map { model ->
            val keyId = model.apiKeyIds.singleOrNull()
            val profileId = keyId?.removePrefix("legacy-key-").orEmpty()
            model.copy(apiKeyIds = if (keyId != null && hasSecret(profileId)) listOf(keyId) else emptyList())
        }
        return migrated.copy(apiKeys = migratedKeys, models = migratedModels).also(::save)
    }

    fun rebuildFromLegacy(
        legacyProfiles: List<ModelProfileMetadata>,
        hasSecret: (profileId: String) -> Boolean
    ): ModelRegistry {
        val previous = preferences.getString(KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(ModelRegistryCodec::decode)
            ?: ModelRegistry()
        val migrated = ModelRegistryCodec.migrateLegacy(legacyProfiles)
        val keys = migrated.apiKeys.mapNotNull { key ->
            val profileId = key.id.removePrefix("legacy-key-")
            if (hasSecret(profileId)) key.copy(enabled = true) else null
        }
        val models = migrated.models.map { model ->
            val profileId = model.id.removePrefix("legacy-model-")
            val old = previous.models.firstOrNull { it.id == model.id }
            model.copy(
                apiKeyIds = if (hasSecret(profileId)) listOf("legacy-key-" + profileId) else emptyList(),
                inputPricePerMillion = old?.inputPricePerMillion,
                outputPricePerMillion = old?.outputPricePerMillion,
                currency = old?.currency ?: model.currency
            )
        }
        return migrated.copy(apiKeys = keys, models = models).also(::save)
    }

    fun save(registry: ModelRegistry) {
        require(registry.validate() == null) { registry.validate() ?: "模型目录无效" }
        preferences.edit().putString(KEY, ModelRegistryCodec.encode(registry)).apply()
    }

    companion object {
        private const val KEY = "model_registry_v1"
    }
}
