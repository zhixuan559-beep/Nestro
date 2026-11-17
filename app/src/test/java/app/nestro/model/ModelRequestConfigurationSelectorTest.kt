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
}
