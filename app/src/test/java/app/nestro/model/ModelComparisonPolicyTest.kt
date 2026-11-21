package app.nestro.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelComparisonPolicyTest {
    private val registry = ModelRegistry(
        providers = listOf(
            ModelProvider("provider-a", "Provider A", "https://a.example.com"),
            ModelProvider("provider-b", "Provider B", "https://b.example.com")
        ),
        apiKeys = listOf(
            ModelApiKey("key-a", "provider-a", "Key A"),
            ModelApiKey("key-b", "provider-b", "Key B")
        ),
        models = listOf(
            ModelDefinition("model-a", "provider-a", "Model A", "model-a-code", listOf("key-a")),
            ModelDefinition("model-b", "provider-b", "Model B", "model-b-code", listOf("key-b"))
        )
    )

    @Test
    fun acceptsTwoDifferentSelectableModelsAndPrompt() {
        assertNull(ModelComparisonPolicy.validate(registry, "model-a", "model-b", "Compare these answers"))
    }

    @Test
    fun rejectsSelectingSameModelTwice() {
        assertEquals(
            "请选择两个不同的模型",
            ModelComparisonPolicy.validate(registry, "model-a", "model-a", "Compare")
        )
    }

    @Test
    fun rejectsUnavailableModel() {
        val disabled = registry.copy(providers = registry.providers.map {
            if (it.id == "provider-b") it.copy(enabled = false) else it
        })
        assertEquals(
            "所选模型不可用，请检查服务商、模型状态和 API Key",
            ModelComparisonPolicy.validate(disabled, "model-a", "model-b", "Compare")
        )
    }

    @Test
    fun rejectsBlankAndOversizedPrompts() {
        assertEquals("请输入需要对比的内容", ModelComparisonPolicy.validate(registry, "model-a", "model-b", "  "))
        assertEquals("对比内容不能超过 12000 个字符", ModelComparisonPolicy.validate(registry, "model-a", "model-b", "x".repeat(12_001)))
    }

    @Test
    fun rejectsMissingModelSelections() {
        assertEquals("请选择两个模型", ModelComparisonPolicy.validate(registry, "", "model-b", "Compare"))
    }
}
