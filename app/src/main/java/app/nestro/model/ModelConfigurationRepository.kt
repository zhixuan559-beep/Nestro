package app.nestro.model

import android.content.Context
import app.nestro.data.LocalVault
import app.nestro.data.ModelProfileMetadata
import app.nestro.data.ModelConfig

/**
 * Application boundary for model metadata and encrypted credentials.
 * UI code works with profile IDs and registry snapshots; storage details stay here.
 */
class ModelConfigurationRepository(context: Context) {
    private val vaultContext = context.applicationContext
    private val registryStore = ModelRegistryStore(vaultContext)

    fun loadRegistry(profiles: List<ModelProfileMetadata>): ModelRegistry =
        withAvailableSecrets(registryStore.load(profiles) { profileId ->
            readProfileKey(profileId).isNotBlank()
        })

    fun rebuildLegacyProfiles(profiles: List<ModelProfileMetadata>): ModelRegistry =
        withAvailableSecrets(registryStore.rebuildFromLegacy(profiles) { profileId ->
            readProfileKey(profileId).isNotBlank()
        })

    private fun withAvailableSecrets(registry: ModelRegistry): ModelRegistry {
        val reconciled = registry.copy(apiKeys = registry.apiKeys.map { key ->
            if (key.enabled && readApiKey(key.id).isBlank()) key.copy(enabled = false) else key
        })
        if (reconciled != registry) registryStore.save(reconciled)
        return reconciled
    }

    fun saveRegistry(registry: ModelRegistry) = registryStore.save(withAvailableSecrets(registry))

    fun readProfileKey(profileId: String): String =
        LocalVault.readProfile(vaultContext, profileId)

    fun writeProfileKey(profileId: String, apiKey: String) =
        LocalVault.writeProfile(vaultContext, profileId, apiKey)

    fun deleteProfileKey(profileId: String) =
        LocalVault.deleteProfile(vaultContext, profileId)

    fun readApiKey(keyId: String): String =
        LocalVault.readApiKey(vaultContext, keyId)

    fun writeApiKey(keyId: String, apiKey: String) =
        LocalVault.writeApiKey(vaultContext, keyId, apiKey)

    fun deleteApiKey(keyId: String) =
        LocalVault.deleteApiKey(vaultContext, keyId)

    fun clearAllApiKeys() =
        LocalVault.clearAllApiKeys(vaultContext)

    /** Keeps the legacy active-model preference format behind the model boundary. */
    internal fun readActiveConfig(): ModelConfig = LocalVault.read(vaultContext)

    internal fun writeActiveConfig(config: ModelConfig) =
        LocalVault.write(vaultContext, config)

    internal fun clearActiveConfig() =
        LocalVault.clearActive(vaultContext)
}
