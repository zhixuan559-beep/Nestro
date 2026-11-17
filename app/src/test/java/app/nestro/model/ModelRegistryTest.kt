package app.nestro.model

import app.nestro.data.ModelProfileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun legacyProfilesMigrateIntoIndependentProviderKeyAndModelEntities() {
        val legacy = listOf(
            ModelProfileMetadata("one", "主模型", "https://api.example.com/v1", "model-a"),
            ModelProfileMetadata("two", "备用模型", "https://api.example.com/v1", "model-b")
        )

        val registry = ModelRegistryCodec.migrateLegacy(legacy)

        assertEquals(1, registry.providers.size)
        assertEquals(2, registry.apiKeys.size)
        assertEquals(2, registry.models.size)
        assertNull(registry.validate())
        assertEquals(2, registry.selectableModels().size)
    }

    @Test
    fun deletingKeyMakesOnlyItsModelsUnselectable() {
        val registry = ModelRegistry(
            providers = listOf(ModelProvider("p", "Provider", "https://api.example.com")),
            apiKeys = listOf(ModelApiKey("k1", "p", "Primary"), ModelApiKey("k2", "p", "Backup")),
            models = listOf(
                ModelDefinition("m1", "p", "Model 1", "one", listOf("k1")),
                ModelDefinition("m2", "p", "Model 2", "two", listOf("k2"))
            )
        )

        val updated = registry.deleteApiKey("k1")

        assertEquals(listOf("m2"), updated.selectableModels().map { it.id })
        assertEquals(2, updated.models.size)
    }

    @Test
    fun codecRoundTripsProviderKeyAndIndependentPricing() {
        val registry = ModelRegistry(
            providers = listOf(ModelProvider("p", "Provider", "https://api.example.com")),
            apiKeys = listOf(ModelApiKey("k", "p", "Primary")),
            models = listOf(ModelDefinition(
                "m", "p", "Model", "model-x", listOf("k"),
                inputPricePerMillion = 0.25,
                outputPricePerMillion = 1.5
            ))
        )

        val decoded = ModelRegistryCodec.decode(ModelRegistryCodec.encode(registry))

        assertEquals(registry, decoded)
        assertTrue(decoded.selectableModels().isNotEmpty())
    }

    @Test
    fun invalidCrossProviderKeyReferenceIsRejected() {
        val registry = ModelRegistry(
            providers = listOf(
                ModelProvider("p1", "One", "https://one.example.com"),
                ModelProvider("p2", "Two", "https://two.example.com")
            ),
            apiKeys = listOf(ModelApiKey("k", "p1", "Key")),
            models = listOf(ModelDefinition("m", "p2", "Model", "m", listOf("k")))
        )

        assertFalse(registry.validate().isNullOrBlank())
    }
}
