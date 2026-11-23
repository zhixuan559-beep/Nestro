package app.nestro.model

import app.nestro.data.ModelProfileMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun providerValidationRejectsInvalidHttpsEndpoints() {
        val invalidUrls = listOf(
            "http://api.example.com",
            "https://",
            "https:///missing-host",
            "https://user:password@api.example.com/v1",
            "https://api.example.com/v1?token=secret",
            "https://api.example.com/v1#fragment"
        )
        invalidUrls.forEachIndexed { index, url ->
            val registry = ModelRegistry(
                providers = listOf(ModelProvider("provider-$index", "Provider", url))
            )
            assertFalse("should reject $url", registry.validate().isNullOrBlank())
        }
    }

    @Test
    fun providerEditKeepsStableIdentityAndExistingKeysAndModels() {
        val original = ModelRegistry(
            providers = listOf(ModelProvider("provider", "Before", "https://before.example.com")),
            apiKeys = listOf(ModelApiKey("key", "provider", "Primary")),
            models = listOf(ModelDefinition("model", "provider", "Model", "model-code", listOf("key")))
        )

        val edited = original.upsertProvider(
            original.providers.single().copy(name = "After", baseUrl = "https://after.example.com")
        )

        assertEquals(listOf("provider"), edited.providers.map { it.id })
        assertEquals("After", edited.providers.single().name)
        assertEquals("https://after.example.com", edited.providers.single().baseUrl)
        assertEquals(original.apiKeys, edited.apiKeys)
        assertEquals(original.models, edited.models)
        assertNull(edited.validate())
    }

    @Test
    fun disabledProviderMakesItsModelsUnselectableWithoutDeletingMetadata() {
        val registry = ModelRegistry(
            providers = listOf(ModelProvider("p", "Provider", "https://api.example.com")),
            apiKeys = listOf(ModelApiKey("k", "p", "Primary")),
            models = listOf(ModelDefinition("m", "p", "Model", "model-code", listOf("k")))
        )

        val disabled = registry.upsertProvider(registry.providers.single().copy(enabled = false))

        assertTrue(disabled.selectableModels().isEmpty())
        assertEquals(1, disabled.apiKeys.size)
        assertEquals(1, disabled.models.size)
        assertTrue(disabled.apiKeys.single().enabled)
        assertNull(disabled.validate())
    }

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
        assertTrue(updated.models.first { it.id == "m1" }.apiKeyIds.isEmpty())
        assertNull(updated.validate())
    }

    @Test
    fun strictDecoderDistinguishesMalformedRegistryFromAnEmptyRegistry() {
        assertEquals(null, ModelRegistryCodec.decodeOrNull("{not-json"))
        assertEquals(ModelRegistry(), ModelRegistryCodec.decodeOrNull(
            ModelRegistryCodec.encode(ModelRegistry())
        ))
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
    fun profileKeyCanBeReplacedWithoutChangingProviderOrModelIdentity() {
        val original = ModelProfileMetadata("profile", "主模型", "https://api.example.com/v1", "model-a")
        val first = ModelRegistry().upsertLegacyProfile(original, keyAvailable = true)
        val changed = first.upsertLegacyProfile(original.copy(name = "新名称", model = "model-b"), keyAvailable = true)

        assertEquals(1, changed.providers.size)
        assertEquals(1, changed.apiKeys.size)
        assertEquals("新名称", changed.models.single().name)
        assertEquals("model-b", changed.models.single().modelCode)
        assertEquals(first.apiKeys.single().id, changed.apiKeys.single().id)
        assertNull(changed.models.single().inputPricePerMillion)
        assertNull(changed.validate())
    }

    @Test
    fun nullablePricesSurviveRegistryCodecRoundTrip() {
        val registry = ModelRegistry(
            providers = listOf(ModelProvider("p", "Provider", "https://api.example.com")),
            models = listOf(ModelDefinition("m", "p", "Model", "model-x", emptyList()))
        )

        assertEquals(registry, ModelRegistryCodec.decode(ModelRegistryCodec.encode(registry)))
    }

    @Test
    fun oneProviderCanOwnMultipleKeysAndModelsIndependently() {
        val provider = ModelProvider("provider", "Provider", "https://api.example.com")
        val registry = ModelRegistry().upsertProvider(provider)
            .upsertApiKey(ModelApiKey("key-a", "provider", "Primary"))
            .upsertApiKey(ModelApiKey("key-b", "provider", "Backup"))
            .upsertModel(ModelDefinition("model-a", "provider", "Model A", "a", listOf("key-a")))
            .upsertModel(ModelDefinition("model-b", "provider", "Model B", "b", listOf("key-b")))

        val attached = registry.attachApiKey("model-a", "key-b")
        assertEquals(setOf("key-a", "key-b"), attached.models.first { it.id == "model-a" }.apiKeyIds.toSet())
        val afterDelete = attached.deleteApiKey("key-a")

        assertEquals(setOf("model-a", "model-b"), afterDelete.selectableModels().map { it.id }.toSet())
        assertTrue(afterDelete.models.first { it.id == "model-a" }.apiKeyIds.contains("key-b"))
        assertNull(afterDelete.validate())
    }

    @Test
    fun legacyProfileUpdatePreservesIndependentEnabledFlagsAndPricing() {
        val profile = ModelProfileMetadata("profile-1", "Profile", "https://api.example.com", "model-v1")
        val initial = ModelRegistry().upsertLegacyProfile(profile, keyAvailable = true)
        val modelId = "legacy-model-profile-1"
        val providerId = ModelRegistryCodec.legacyProviderId(profile.baseUrl)
        val disabled = initial.copy(
            providers = initial.providers.map { it.copy(enabled = false) },
            apiKeys = initial.apiKeys.map { it.copy(enabled = false) },
            models = initial.models.map {
                it.copy(enabled = false, inputPricePerMillion = 1.25, outputPricePerMillion = 2.5, currency = "CNY")
            }
        )

        val updated = disabled.upsertLegacyProfile(profile.copy(model = "model-v2"), keyAvailable = true)

        assertFalse(updated.providers.first { it.id == providerId }.enabled)
        assertFalse(updated.apiKeys.first { it.id == "legacy-key-profile-1" }.enabled)
        val model = updated.models.first { it.id == modelId }
        assertFalse(model.enabled)
        assertEquals("model-v2", model.modelCode)
        assertEquals(1.25, model.inputPricePerMillion!!, 0.0)
        assertEquals(2.5, model.outputPricePerMillion!!, 0.0)
        assertEquals("CNY", model.currency)

        val replacedKey = disabled.upsertLegacyProfile(
            profile.copy(model = "model-v2"),
            keyAvailable = true,
            enableKey = true
        )
        assertTrue(replacedKey.apiKeys.first { it.id == "legacy-key-profile-1" }.enabled)
        assertFalse(replacedKey.providers.first { it.id == providerId }.enabled)
    }

    @Test
    fun deletingProviderRemovesItsModelsAndKeysOnly() {
        val registry = ModelRegistry(
            providers = listOf(
                ModelProvider("one", "One", "https://one.example.com"),
                ModelProvider("two", "Two", "https://two.example.com")
            ),
            apiKeys = listOf(
                ModelApiKey("one-key", "one", "One"),
                ModelApiKey("two-key", "two", "Two")
            ),
            models = listOf(
                ModelDefinition("one-model", "one", "One", "one", listOf("one-key")),
                ModelDefinition("two-model", "two", "Two", "two", listOf("two-key"))
            )
        )

        val updated = registry.deleteProvider("one")

        assertEquals(listOf("two"), updated.providers.map { it.id })
        assertEquals(listOf("two-key"), updated.apiKeys.map { it.id })
        assertEquals(listOf("two-model"), updated.models.map { it.id })
        assertNull(updated.validate())
    }

    @Test
    fun legacyReconciliationPreservesIndependentlyManagedModels() {
        val legacyProfile = ModelProfileMetadata("legacy", "Legacy", "https://legacy.example.com/v1", "legacy-model")
        val custom = ModelRegistry(
            providers = listOf(ModelProvider("custom-provider", "Custom", "https://custom.example.com")),
            apiKeys = listOf(ModelApiKey("custom-key", "custom-provider", "Custom key")),
            models = listOf(ModelDefinition(
                "custom-model", "custom-provider", "Custom model", "custom-model-code", listOf("custom-key"),
                inputPricePerMillion = 2.0,
                outputPricePerMillion = 4.0
            ))
        )

        val reconciled = custom.reconcileLegacyProfiles(listOf(legacyProfile)) { true }

        assertTrue(reconciled.providers.any { it.id == "custom-provider" })
        assertTrue(reconciled.apiKeys.any { it.id == "custom-key" })
        assertTrue(reconciled.models.any { it.id == "custom-model" })
        assertEquals(2.0, reconciled.models.first { it.id == "custom-model" }.inputPricePerMillion!!, 0.0)
        assertEquals(listOf("legacy-model-legacy", "custom-model").toSet(), reconciled.models.map { it.id }.toSet())
        assertNull(reconciled.validate())
    }

    @Test
    fun legacyReconciliationRemovesMissingSecretsWithoutDeletingModelMetadata() {
        val profile = ModelProfileMetadata("missing", "Missing key", "https://api.example.com/v1", "model-x")
        val initial = ModelRegistry().upsertLegacyProfile(profile, keyAvailable = true)

        val reconciled = initial.reconcileLegacyProfiles(listOf(profile)) { false }

        assertTrue(reconciled.apiKeys.none { it.id == "legacy-key-missing" })
        assertTrue(reconciled.models.any { it.id == "legacy-model-missing" && it.apiKeyIds.isEmpty() })
        assertTrue(reconciled.selectableModels().isEmpty())
        assertNull(reconciled.validate())
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


    @Test
    fun editingRegistryModelPreservesIndependentMetadataAndStableIdentity() {
        val original = ModelDefinition(
            id = "model-id",
            providerId = "provider-id",
            name = "Original",
            modelCode = "model-v1",
            apiKeyIds = listOf("key-a", "key-b"),
            enabled = false,
            inputPricePerMillion = 0.75,
            outputPricePerMillion = 1.5,
            currency = "CNY"
        )
        val registry = ModelRegistry(
            providers = listOf(ModelProvider("provider-id", "Provider", "https://api.example.com")),
            apiKeys = listOf(
                ModelApiKey("key-a", "provider-id", "A"),
                ModelApiKey("key-b", "provider-id", "B")
            ),
            models = listOf(original)
        )

        val edited = registry.upsertModel(original.copy(name = "Renamed", modelCode = "model-v2"))

        val saved = edited.models.single()
        assertEquals("model-id", saved.id)
        assertEquals("Renamed", saved.name)
        assertEquals("model-v2", saved.modelCode)
        assertEquals(listOf("key-a", "key-b"), saved.apiKeyIds)
        assertFalse(saved.enabled)
        assertEquals(0.75, saved.inputPricePerMillion!!, 0.0)
        assertEquals(1.5, saved.outputPricePerMillion!!, 0.0)
        assertEquals("CNY", saved.currency)
        assertNull(edited.validate())
    }

    @Test
    fun reasoningCapabilityRoundTripsAndDefaultsOffForOlderRegistryPayloads() {
        val registry = ModelRegistry(
            providers = listOf(ModelProvider("p", "Provider", "https://api.example.com")),
            apiKeys = listOf(ModelApiKey("k", "p", "Key")),
            models = listOf(ModelDefinition("m", "p", "Model", "model", listOf("k"), supportsReasoningEffort = true))
        )
        val encoded = ModelRegistryCodec.encode(registry)
        val decoded = ModelRegistryCodec.decodeOrNull(encoded)!!
        assertTrue(decoded.models.single().supportsReasoningEffort)
        val oldJson = JSONObject(encoded)
        oldJson.getJSONArray("models").getJSONObject(0).remove("supportsReasoningEffort")
        val oldDecoded = ModelRegistryCodec.decodeOrNull(oldJson.toString())!!
        assertFalse(oldDecoded.models.single().supportsReasoningEffort)
    }

    @Test
    fun inputModalitiesAndNativeToolCallingRoundTripWithSafeLegacyDefaults() {
        val registry = ModelRegistry(
            providers = listOf(ModelProvider("p", "Provider", "https://api.example.com")),
            apiKeys = listOf(ModelApiKey("k", "p", "Key")),
            models = listOf(ModelDefinition(
                "m", "p", "Model", "model", listOf("k"),
                inputModalities = setOf(ModelModality.TEXT, ModelModality.IMAGE, ModelModality.AUDIO),
                supportsNativeToolCalling = false
            ))
        )
        val encoded = ModelRegistryCodec.encode(registry)
        val decoded = ModelRegistryCodec.decodeOrNull(encoded)!!
        assertEquals(setOf(ModelModality.TEXT, ModelModality.IMAGE, ModelModality.AUDIO), decoded.models.single().inputModalities)
        assertFalse(decoded.models.single().supportsNativeToolCalling)

        val oldJson = JSONObject(encoded)
        oldJson.getJSONArray("models").getJSONObject(0).apply {
            remove("inputModalities")
            remove("supportsNativeToolCalling")
        }
        val oldDecoded = ModelRegistryCodec.decodeOrNull(oldJson.toString())!!
        assertEquals(setOf(ModelModality.TEXT), oldDecoded.models.single().inputModalities)
        assertTrue(oldDecoded.models.single().supportsNativeToolCalling)
    }

    @Test
    fun providerPresetProtocolAndIdentityRoundTripWithLegacyDefaults() {
        val provider = ModelProvider(
            id = "preset-provider-anthropic",
            name = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com",
            presetId = "anthropic",
            protocol = ModelApiProtocol.ANTHROPIC_MESSAGES
        )
        val registry = ModelRegistry(providers = listOf(provider))
        val decoded = ModelRegistryCodec.decodeOrNull(ModelRegistryCodec.encode(registry))!!
        assertEquals("anthropic", decoded.providers.single().presetId)
        assertEquals(ModelApiProtocol.ANTHROPIC_MESSAGES, decoded.providers.single().protocol)

        val legacyPayload = """{"version":1,"providers":[{"id":"legacy","name":"Legacy","baseUrl":"https://api.example.com","enabled":true}],"apiKeys":[],"models":[]}"""
        val legacyDecoded = ModelRegistryCodec.decodeOrNull(legacyPayload)!!
        assertEquals("", legacyDecoded.providers.single().presetId)
        assertEquals(
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS,
            legacyDecoded.providers.single().protocol
        )
    }

    @Test
    fun builtInProviderCatalogContainsExpectedOfficialAndCommunityPresets() {
        val ids = ModelProviderPresetCatalog.all.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("openai", "deepseek", "anthropic", "siliconflow", "modelscope", "gemini", "dashscope")))
        assertEquals(
            ModelApiProtocol.ANTHROPIC_MESSAGES,
            ModelProviderPresetCatalog.byId("anthropic")!!.protocol
        )
    }


    @Test
    fun siliconFlowAndModelScopePresetsExposeOfficialCredentialPortals() {
        val siliconFlow = ModelProviderPresetCatalog.byId("siliconflow")!!
        assertEquals("https://api.siliconflow.cn/v1", siliconFlow.baseUrl)
        assertEquals("https://cloud.siliconflow.cn/account/ak", siliconFlow.accountUrl)
        assertEquals(ModelApiProtocol.OPENAI_CHAT_COMPLETIONS, siliconFlow.protocol)

        val modelScope = ModelProviderPresetCatalog.byId("modelscope")!!
        assertEquals("https://api-inference.modelscope.cn/v1", modelScope.baseUrl)
        assertEquals("https://modelscope.cn/my/myaccesstoken", modelScope.accountUrl)
        assertEquals(ModelApiProtocol.OPENAI_CHAT_COMPLETIONS, modelScope.protocol)
    }
}
