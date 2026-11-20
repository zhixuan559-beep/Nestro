package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticMemoryExtractionCoordinatorTest {
    @Test
    fun extractsAndPersistsCandidatesOnlyWhenEnabledAndPromptHasLongTermSignal() = kotlinx.coroutines.runBlocking {
        val prompt = "我喜欢简洁直接的表达方式，以后写作请保持这个风格。"
        var extractionCalls = 0
        val saved = mutableListOf<MemoryCandidate>()
        val coordinator = AutomaticMemoryExtractionCoordinator(
            extractCandidates = { _, messages ->
                extractionCalls += 1
                assertEquals(listOf(prompt), messages)
                listOf(MemoryCandidate("preference", "偏好简洁直接的表达方式", 0.95))
            },
            saveCandidates = { projectId, conversationId, messageId, candidates ->
                assertEquals("project-1", projectId)
                assertEquals("conversation-1", conversationId)
                assertEquals("message-1", messageId)
                saved += candidates
            }
        )
        coordinator.extractAndSave(
            enabled = true, prompt = prompt, modelConfigs = emptyList(),
            projectId = "project-1", conversationId = "conversation-1", messageId = "message-1"
        )
        assertEquals(1, extractionCalls)
        assertEquals(1, saved.size)
        assertEquals("偏好简洁直接的表达方式", saved.single().content)
    }

    @Test
    fun skipsDisabledAndLowSignalPrompts() = kotlinx.coroutines.runBlocking {
        var extractionCalls = 0
        val coordinator = AutomaticMemoryExtractionCoordinator(
            extractCandidates = { _, _ -> extractionCalls += 1; emptyList() },
            saveCandidates = { _, _, _, _ -> }
        )
        coordinator.extractAndSave(
            enabled = false, prompt = "我喜欢简洁直接的表达方式，以后写作请保持这个风格。",
            modelConfigs = emptyList(), projectId = "p", conversationId = "c", messageId = "m"
        )
        coordinator.extractAndSave(
            enabled = true, prompt = "今天帮我总结这段文字",
            modelConfigs = emptyList(), projectId = "p", conversationId = "c", messageId = "m"
        )
        assertEquals(0, extractionCalls)
    }

    @Test
    fun skipsBlankMessageIdsAndSensitivePromptsBeforeCallingExtractor() = kotlinx.coroutines.runBlocking {
        var extractionCalls = 0
        val coordinator = AutomaticMemoryExtractionCoordinator(
            extractCandidates = { _, _ -> extractionCalls += 1; emptyList() },
            saveCandidates = { _, _, _, _ -> }
        )
        val ordinaryPrompt = "我喜欢简洁直接的表达方式，以后写作请保持这个风格。"
        coordinator.extractAndSave(
            enabled = true, prompt = ordinaryPrompt, modelConfigs = emptyList(),
            projectId = "p", conversationId = "c", messageId = ""
        )
        coordinator.extractAndSave(
            enabled = true, prompt = "以后请记住我的银行卡号是 6222021234567890123",
            modelConfigs = emptyList(), projectId = "p", conversationId = "c", messageId = "m"
        )
        assertEquals(0, extractionCalls)
    }
}
