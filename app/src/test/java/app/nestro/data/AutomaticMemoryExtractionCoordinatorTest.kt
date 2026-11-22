package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticMemoryExtractionCoordinatorTest {
    @Test
    fun extractsAndPersistsCandidatesOnlyWhenEnabledAndPromptHasLongTermSignal() = kotlinx.coroutines.runBlocking {
        val prompt = "我喜欢简洁直接的表达方式，以后写作请保持这个风格。"
        var extractionCalls = 0
        val saved = mutableListOf<MemoryCandidate>()
        val expectedVersion = MemoryMutationToken(7L, 3L)
        val coordinator = AutomaticMemoryExtractionCoordinator(
            extractCandidates = { _, messages, existing ->
                extractionCalls += 1
                assertEquals(listOf(prompt), messages)
                assertEquals(emptyList<UserMemoryEntity>(), existing)
                listOf(MemoryCandidate("preference", "偏好简洁直接的表达方式", 0.95))
            },
            saveCandidates = { projectId, conversationId, messageId, candidates, mutationVersion ->
                assertEquals("project-1", projectId)
                assertEquals("conversation-1", conversationId)
                assertEquals("message-1", messageId)
                assertEquals(expectedVersion, mutationVersion)
                saved += candidates
            },
            captureMutationVersion = { expectedVersion }
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
            extractCandidates = { _, _, _ -> extractionCalls += 1; emptyList() },
            saveCandidates = { _, _, _, _, _ -> }
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
            extractCandidates = { _, _, _ -> extractionCalls += 1; emptyList() },
            saveCandidates = { _, _, _, _, _ -> }
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
    @Test
    fun preparesOnlySafeRecentUserMessagesAndRecognizesEnglishCorrections() {
        val prepared = prepareMemoryExtractionInput(
            listOf(
                "今天讨论了普通任务",
                "我偏好清晰、简洁的项目说明",
                "我的银行卡号是 6222021234567890123",
                "From now on, I prefer short technical answers."
            )
        )
        assertEquals(2, prepared.size)
        assertEquals("我偏好清晰、简洁的项目说明", prepared[0])
        assertEquals("From now on, I prefer short technical answers.", prepared[1])
        assertEquals(true, shouldExtractAutomaticMemory(prepared))
    }

    @Test
    fun memoryRecallQueryUsesRecentUserTurnsAndCurrentPromptWithinBudget() {
        val query = buildMemoryRecallQuery(
            listOf("第一次说明", "第二次说明", "第二次说明"),
            "以后按这个新规则执行",
            maxChars = 40
        )
        assertEquals("第一次说明\\n第二次说明\\n以后按这个新规则执行", query)
    }

    @Test
    fun suppliesRelevantOlderMemoriesAlongsideRecentContextForExplicitCorrections() = kotlinx.coroutines.runBlocking {
        val recent = (1..40).map { index ->
            UserMemoryEntity(
                id = "recent-$index",
                projectId = "project-1",
                category = "project",
                content = "项目近期记录编号 $index 的普通背景信息",
                fingerprint = "recent-fingerprint-$index",
                sourceConversationId = "conversation",
                sourceMessageId = "message-$index",
                confidence = 0.9,
                createdAt = index.toLong(),
                updatedAt = index.toLong()
            )
        }
        val olderRelevant = UserMemoryEntity(
            id = "older-writing-preference",
            projectId = "project-1",
            category = "preference",
            content = "写作偏好简洁直接的技术文章风格",
            fingerprint = "older-writing-fingerprint",
            sourceConversationId = "conversation",
            sourceMessageId = "old-message",
            confidence = 0.98,
            createdAt = 1L,
            updatedAt = 1L
        )
        var supplied = emptyList<UserMemoryEntity>()
        val prompt = "以后写作请保持技术文章简洁直接的风格。"
        val coordinator = AutomaticMemoryExtractionCoordinator(
            extractCandidates = { _, _, existing ->
                supplied = existing
                emptyList()
            },
            saveCandidates = { _, _, _, _, _ -> },
            loadExistingMemories = { recent + olderRelevant }
        )

        coordinator.extractAndSave(
            enabled = true,
            prompt = prompt,
            modelConfigs = emptyList(),
            projectId = "project-1",
            conversationId = "conversation",
            messageId = "message-current"
        )

        assertTrue(supplied.size <= 30)
        assertTrue(supplied.any { it.id == "older-writing-preference" })
        assertTrue(supplied.any { it.id == "recent-40" })
    }

    @Test
    fun capturesMemoryVersionBeforeExtractionAndPassesItToSave() = kotlinx.coroutines.runBlocking {
        val initial = MemoryMutationToken(4L, 2L)
        val changedDuringExtraction = MemoryMutationToken(5L, 2L)
        var current = initial
        var savedToken: MemoryMutationToken? = null
        val coordinator = AutomaticMemoryExtractionCoordinator(
            extractCandidates = { _, _, _ ->
                current = changedDuringExtraction
                listOf(MemoryCandidate("preference", "偏好简洁直接的表达方式", 0.95))
            },
            saveCandidates = { _, _, _, _, token -> savedToken = token },
            captureMutationVersion = { current }
        )
        coordinator.extractAndSave(
            enabled = true,
            prompt = "我喜欢简洁直接的表达方式，以后写作请保持这个风格。",
            modelConfigs = emptyList(),
            projectId = "stale-extraction-project",
            conversationId = "conversation",
            messageId = "message"
        )
        assertEquals(initial, savedToken)
        assertEquals(changedDuringExtraction, current)
    }

}
