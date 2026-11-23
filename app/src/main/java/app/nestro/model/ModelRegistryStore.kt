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
            val decoded = ModelRegistryCodec.decodeOrNull(saved)
            if (decoded != null && (decoded.providers.isNotEmpty() || legacyProfiles.isEmpty())) return decoded
            preserveUnreadableRegistry(saved)
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
        val saved = preferences.getString(KEY, null)?.takeIf { it.isNotBlank() }
        val previous = saved?.let(ModelRegistryCodec::decodeOrNull) ?: ModelRegistry().also {
            if (saved != null) preserveUnreadableRegistry(saved)
        }
        return previous.reconcileLegacyProfiles(legacyProfiles, hasSecret).also(::save)
    }

    fun save(registry: ModelRegistry) {
        require(registry.validate() == null) { registry.validate() ?: "模型目录无效" }
        preferences.edit().putString(KEY, ModelRegistryCodec.encode(registry)).apply()
    }

    private fun preserveUnreadableRegistry(raw: String) {
        // Preserve the original payload before migration overwrites the primary key.
        // Keep the first quarantined copy until an explicit recovery/cleanup flow exists.
        if (!preferences.contains(CORRUPT_BACKUP_KEY)) {
            preferences.edit()
                .putString(CORRUPT_BACKUP_KEY, raw)
                .putLong(CORRUPT_BACKUP_TIME_KEY, System.currentTimeMillis())
                .apply()
        }
    }

    companion object {
        private const val KEY = "model_registry_v1"
        private const val CORRUPT_BACKUP_KEY = "model_registry_v1_unreadable_backup"
        private const val CORRUPT_BACKUP_TIME_KEY = "model_registry_v1_unreadable_backup_at"
    }
}
