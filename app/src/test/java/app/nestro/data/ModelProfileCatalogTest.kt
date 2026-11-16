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
}
