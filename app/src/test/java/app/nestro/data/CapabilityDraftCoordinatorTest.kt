package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDraftCoordinatorTest {
    @Test
    fun generatesExpertDraftWithExistingNamesAndParsesStrictDefinition() {
        var observedSystem = ""
        val coordinator = CapabilityDraftCoordinator(
            CapabilityDraftTransport { _, history, systemPrompt, _ ->
                assertEquals("创建一个擅长研究的专家", history.single().content)
                observedSystem = systemPrompt
                """{"name":"研究专家","prompt":"${"具体执行规则".repeat(20)}"}"""
            }
        )
        val result = coordinator.generate(
            kind = "expert",
            prompt = "创建一个擅长研究的专家",
            existingNames = "写作专家、运营专家",
            modelConfigs = emptyList(),
            onUsage = { _, _, _ -> }
        )
        assertEquals("研究专家", result.first)
        assertTrue(result.second.length >= 80)
        assertTrue(observedSystem.contains("AI 专家"))
        assertTrue(observedSystem.contains("写作专家、运营专家"))
    }

    @Test
    fun rejectsNegatedCreationIntentAndInvalidGeneratedDefinition() {
        assertFalse(isCreationIntent("不要创建专家", "专家"))
        assertTrue(isCreationIntent("帮我设计一个专家", "专家"))
        val invalid = runCatching { parseGeneratedDefinition("""{"name":"x","prompt":"太短"}""") }
        assertTrue(invalid.isFailure)
    }
}
