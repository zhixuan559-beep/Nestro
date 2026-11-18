package app.nestro.data

import org.junit.Assert.*
import org.junit.Test

class ModelProfileCatalogTest {
    private fun profile(id: String = "a", name: String = "主模型") =
        ModelProfileMetadata(id, name, "https://api.example.com/v1", "example-chat")

    @Test fun roundTripsMetadataWithoutSecrets() {
        val profiles = listOf(profile(), profile("b", "备用模型").copy(model = "other-model"))
        val encoded = ModelProfileCatalogCodec.encode(profiles)
        assertEquals(profiles, ModelProfileCatalogCodec.decode(encoded))
        assertFalse(encoded.contains("api_key"))
        assertFalse(encoded.contains("secret"))
    }

    @Test fun rejectsInsecureEndpointsAndBlankModels() {
        assertNotNull(ModelProfileCatalogCodec.validate(profile().copy(baseUrl = "http://api.example.com")))
        assertNotNull(ModelProfileCatalogCodec.validate(profile().copy(model = " ")))
    }

    @Test fun rejectsDuplicateNamesAndIdsAndExcessProfiles() {
        assertNotNull(ModelProfileCatalogCodec.validateCatalog(listOf(profile(), profile("b", "主模型"))))
        assertNotNull(ModelProfileCatalogCodec.validateCatalog(listOf(profile(), profile())))
        val many = (1..21).map { profile(it.toString(), "模型$it") }
        assertNotNull(ModelProfileCatalogCodec.validateCatalog(many))
    }

    @Test fun ignoresMalformedCatalogInsteadOfPartiallyLoadingIt() {
        assertTrue(ModelProfileCatalogCodec.decode("not-json").isEmpty())
        assertTrue(ModelProfileCatalogCodec.decode("[]").isEmpty())
    }
    
    @Test fun recordsApproximateUsagePerProfile() {
        val first = ModelUsageStatsCodec.record(emptyMap(), "a", 5, 9, 100L)
        val second = ModelUsageStatsCodec.record(first, "a", 4, 4, 200L)
        val stats = second.getValue("a")
        assertEquals(2L, stats.requests)
        assertEquals(3L, stats.estimatedInputTokens)
        assertEquals(4L, stats.estimatedOutputTokens)
        assertEquals(200L, stats.lastUsedAt)
        assertEquals(second, ModelUsageStatsCodec.decode(ModelUsageStatsCodec.encode(second)))
    }

    @Test fun recordsProviderReportedUsageSeparatelyFromEstimates() {
        val estimated = ModelUsageStatsCodec.record(emptyMap(), "a", 40, 20, 100L)
        val mixed = ModelUsageStatsCodec.recordProviderUsage(estimated, "a", 11, 7, 200L)
        val value = mixed.getValue("a")
        assertEquals(2L, value.requests)
        assertEquals(10L, value.estimatedInputTokens)
        assertEquals(5L, value.estimatedOutputTokens)
        assertEquals(11L, value.providerInputTokens)
        assertEquals(7L, value.providerOutputTokens)
        assertEquals(1L, value.providerUsageRequests)
        assertEquals(1L, value.estimatedUsageRequests)
        assertEquals(mixed, ModelUsageStatsCodec.decode(ModelUsageStatsCodec.encode(mixed)))
    }

    @Test fun decodesLegacyUsageAsEstimatedOnly() {
        val decoded = ModelUsageStatsCodec.decode("""{"a":{"requests":3,"input_tokens":12,"output_tokens":8,"last_used_at":99}}""")
        val value = decoded.getValue("a")
        assertEquals(12L, value.estimatedInputTokens)
        assertEquals(8L, value.estimatedOutputTokens)
        assertEquals(0L, value.providerInputTokens)
        assertEquals(0L, value.providerUsageRequests)
    }
}
