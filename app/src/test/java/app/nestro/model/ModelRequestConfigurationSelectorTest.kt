package app.nestro.model

import app.nestro.data.ModelConfig
import app.nestro.data.ModelProfileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestConfigurationSelectorTest {
    private fun registry(): ModelRegistry {
        val provider = ModelProvider("provider", "Provider", "https://api.example.com")
        val keyA = ModelApiKey("legacy-key-a", provider.id, "A")
        val keyB = ModelApiKey("legacy-key-b", provider.id, "B")
        val modelA = ModelDefinition("legacy-model-a", provider.id, "A", "model-a", listOf(keyA.id))
        val modelB = ModelDefinition("legacy-model-b", provider.id, "B", "model-b", listOf(keyB.id))
        return ModelRegistry(listOf(provider), listOf(keyA, keyB), listOf(modelA, modelB))
    }

    private val profiles = listOf(
        ModelProfileMetadata("a", "A", "https://api.example.com", "model-a"),
        ModelProfileMetadata("b", "B", "https://api.example.com", "model-b")
    )

    @Test
    fun activeModelIsIncludedOnlyWhenItsCredentialIsSelectable() {
        val configs = ModelRequestConfigurationSelector.buildRequestConfigs(
            registry = registry(),
            profiles = profiles,
            activeProfileId = "a",
            activeConfig = ModelConfig("https://api.example.com", "model-a", "secret-a"),
            failoverEnabled = false,
            readKey = { "" }
        )

        assertEquals(listOf("model-a"), configs.map { it.model })
    }

    @Test
    fun failoverAddsOnlyOtherSelectableProfilesWithAvailableKeys() {
        val configs = ModelRequestConfigurationSelector.buildRequestConfigs(
            registry = registry(),
            profiles = profiles,
            activeProfileId = "a",
            activeConfig = ModelConfig("https://api.example.com", "model-a", "secret-a"),
            failoverEnabled = true,
            readKey = { id -> if (id == "b") "secret-b" else "" }
        )

        assertEquals(listOf("model-a", "model-b"), configs.map { it.model })
        assertEquals(listOf("secret-a", "secret-b"), configs.map { it.apiKey })
    }

    @Test
    fun disabledFailoverDoesNotReadOrAddFallbackCredentials() {
        var reads = 0
        val configs = ModelRequestConfigurationSelector.buildRequestConfigs(
            registry = registry(),
            profiles = profiles,
            activeProfileId = "a",
            activeConfig = ModelConfig("https://api.example.com", "model-a", "secret-a"),
            failoverEnabled = false,
            readKey = { reads++; "secret-b" }
        )

        assertEquals(1, configs.size)
        assertEquals(0, reads)
        assertTrue(configs.none { it.model == "model-b" })
    }

    @Test
    fun duplicateEndpointAndModelPairsAreDeduplicated() {
        val duplicateProfiles = profiles + ModelProfileMetadata(
            "c", "C", "https://api.example.com", "model-b"
        )
        val withDuplicateRegistry = registry().upsertLegacyProfile(duplicateProfiles.last(), true)
        val configs = ModelRequestConfigurationSelector.buildRequestConfigs(
            registry = withDuplicateRegistry,
            profiles = duplicateProfiles,
            activeProfileId = "a",
            activeConfig = ModelConfig("https://api.example.com", "model-a", "secret-a"),
            failoverEnabled = true,
            readKey = { "secret" }
        )

        assertEquals(2, configs.size)
    }

    @Test
    fun registrySelectionDoesNotChooseArbitraryModelWhenActiveSelectionIsMissing() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(ModelApiKey("key", "p", "Key")),
            models = listOf(ModelDefinition("model", "p", "Model", "model-a", listOf("key")))
        )
        var reads = 0

        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry, "", true
        ) { reads++; "secret" }

        assertTrue(configs.isEmpty())
        assertEquals(0, reads)
    }

    @Test
    fun registrySelectionDoesNotChooseFallbackForStaleActiveModelId() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(ModelApiKey("key", "p", "Key")),
            models = listOf(ModelDefinition("available", "p", "Available", "model-a", listOf("key")))
        )

        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry, "deleted-model", true
        ) { "secret" }

        assertTrue(configs.isEmpty())
    }

    @Test
    fun registrySelectionUsesActiveModelAndOrderedFailoverKeys() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(
                ModelApiKey("active-key", "p", "Active"),
                ModelApiKey("backup-key", "p", "Backup")
            ),
            models = listOf(
                ModelDefinition("active", "p", "Active", "model-a", listOf("active-key")),
                ModelDefinition("backup", "p", "Backup", "model-b", listOf("backup-key"))
            )
        )

        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry, "active", true
        ) { id -> if (id == "active-key") "secret-a" else "secret-b" }

        assertEquals(listOf("model-a", "model-b"), configs.map { it.model })
        assertEquals(listOf("secret-a", "secret-b"), configs.map { it.apiKey })
    }

    @Test
    fun registrySelectionSkipsDisabledModelsKeysAndMissingSecrets() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(
                ModelApiKey("missing", "p", "Missing"),
                ModelApiKey("disabled", "p", "Disabled", enabled = false),
                ModelApiKey("valid", "p", "Valid")
            ),
            models = listOf(
                ModelDefinition("active", "p", "Active", "model-a", listOf("missing", "disabled")),
                ModelDefinition("backup", "p", "Backup", "model-b", listOf("valid"))
            )
        )

        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry, "active", true
        ) { id -> if (id == "valid") "secret-b" else "" }

        assertEquals(listOf("model-b"), configs.map { it.model })
    }

    @Test
    fun registrySelectionDoesNotReadFallbackKeysWhenFailoverIsDisabled() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(
                ModelApiKey("active-key", "p", "Active"),
                ModelApiKey("backup-key", "p", "Backup")
            ),
            models = listOf(
                ModelDefinition("active", "p", "Active", "model-a", listOf("active-key")),
                ModelDefinition("backup", "p", "Backup", "model-b", listOf("backup-key"))
            )
        )
        val reads = mutableListOf<String>()

        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry, "active", false
        ) { id -> reads += id; "secret" }

        assertEquals(listOf("model-a"), configs.map { it.model })
        assertEquals(listOf("active-key"), reads)
    }

    @Test
    fun registrySelectionKeepsMultipleKeysForTheSameModelAsFallbackCandidates() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(
                ModelApiKey("primary-key", "p", "Primary"),
                ModelApiKey("backup-key", "p", "Backup")
            ),
            models = listOf(
                ModelDefinition("active", "p", "Active", "model-a", listOf("primary-key", "backup-key"))
            )
        )

        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry, "active", false
        ) { id -> if (id == "primary-key") "secret-a" else "secret-b" }

        assertEquals(listOf("secret-a", "secret-b"), configs.map { it.apiKey })
        assertEquals(listOf("model-a", "model-a"), configs.map { it.model })
    }

    @Test
    fun registrySelectionSkipsDisabledKeyEvenWhenSecretStillExists() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(
                ModelApiKey("disabled-key", "p", "Disabled", enabled = false),
                ModelApiKey("enabled-key", "p", "Enabled")
            ),
            models = listOf(
                ModelDefinition("active", "p", "Active", "model-a", listOf("disabled-key", "enabled-key"))
            )
        )
        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry, "active", false
        ) { id -> if (id == "disabled-key") "stale-secret" else "valid-secret" }

        assertEquals(listOf("valid-secret"), configs.map { it.apiKey })
    }

    @Test
    fun registryReasoningEffortIsPassedOnlyForModelsThatDeclareSupport() {
        val provider = ModelProvider("p", "Provider", "https://api.example.com")
        val registry = ModelRegistry(
            providers = listOf(provider),
            apiKeys = listOf(ModelApiKey("k1", "p", "K1"), ModelApiKey("k2", "p", "K2")),
            models = listOf(
                ModelDefinition("active", "p", "Active", "active", listOf("k1"), supportsReasoningEffort = true),
                ModelDefinition("backup", "p", "Backup", "backup", listOf("k2"))
            )
        )
        val configs = ModelRequestConfigurationSelector.buildRegistryRequestConfigs(
            registry = registry, activeModelId = "active", failoverEnabled = true,
            reasoningEffort = "medium", readKey = { "secret" }
        )
        assertEquals("medium", configs.first().reasoningEffort)
        assertEquals(null, configs.last().reasoningEffort)
    }
}
