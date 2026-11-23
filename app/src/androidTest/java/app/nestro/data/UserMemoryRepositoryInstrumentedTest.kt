package app.nestro.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserMemoryRepositoryInstrumentedTest {
    private fun memory() = UserMemoryEntity(
        id = "source-memory",
        projectId = "project-a",
        category = "preference",
        content = "偏好清晰直接的表达方式",
        fingerprint = UserMemoryStore.fingerprint("preference|偏好清晰直接的表达方式"),
        sourceConversationId = "conversation-a",
        sourceMessageId = "message-a",
        confidence = 0.94,
        createdAt = 100L,
        updatedAt = 200L
    )

    @Test
    fun importEditDeleteRoundTripStaysInsideTargetProjectAndDeduplicates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            dao.putUserMemory(memory())
            dao.putConversation(ConversationEntity("conversation-a", "来源会话", 200L, "project-a"))
            val repository = UserMemoryRepository(database)
            val exported = UserMemoryExportCodec.encode(memory())

            assertTrue(repository.importOne("project-b", exported))
            assertFalse("重复导入不得创建第二条记忆", repository.importOne("project-b", exported))
            assertEquals(listOf("source-memory"), repository.list("project-a").map { it.id })
            val imported = repository.list("project-b").single()
            assertEquals("project-b", imported.projectId)
            assertEquals("conversation-a", imported.sourceConversationId)
            assertEquals("message-a", imported.sourceMessageId)

            // Removing the original conversation must not delete a memory imported into another project.
            dao.deleteConversations(listOf("conversation-a"))
            assertTrue(repository.list("project-a").isEmpty())
            assertEquals(1, repository.list("project-b").size)

            repository.edit(imported.id, "project-b", "表达风格保持清楚简洁", "workflow")
            val edited = repository.list("project-b").single()
            assertEquals("workflow", edited.category)
            assertEquals("表达风格保持清楚简洁", edited.content)
            assertTrue(repository.list("project-a").isEmpty())

            repository.delete(edited.id, "project-b")
            assertTrue(repository.list("project-b").isEmpty())
            assertTrue(repository.list("project-a").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun clearingAutomaticMemoriesOnlyAffectsTheSelectedProject() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            dao.putUserMemory(memory())
            dao.putUserMemory(memory().copy(
                id = "other-project-memory",
                projectId = "project-b",
                content = "另一项目的独立记忆",
                fingerprint = UserMemoryStore.fingerprint("preference|另一项目的独立记忆")
            ))
            val repository = UserMemoryRepository(database)
            repository.clearProject("project-a")
            assertTrue(repository.list("project-a").isEmpty())
            assertEquals(listOf("other-project-memory"), repository.list("project-b").map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun conversationPersistenceCreatesSourceAndRejectsCrossProjectOverwrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("project-a", "项目 A", "", 1L),
                ProjectEntity("project-b", "项目 B", "", 1L)
            ))
            val persistence = ConversationPersistenceService(database)
            persistence.persistConversationMessages(
                conversationId = "conversation-a",
                projectId = "project-a",
                messages = listOf(ChatMessage("message-a", "user", "偏好清晰直接的表达方式"))
            )
            val persisted = requireNotNull(dao.conversationWithMessages("conversation-a"))
            assertEquals("project-a", persisted.conversation.projectId)
            assertEquals(listOf("message-a"), persisted.messages.map { it.id })

            assertTrue(
                runCatching {
                    persistence.persistConversationMessages(
                        conversationId = "conversation-a",
                        projectId = "project-b",
                        messages = listOf(ChatMessage("message-b", "user", "另一项目的消息"))
                    )
                }.isFailure
            )
            val unchanged = requireNotNull(dao.conversationWithMessages("conversation-a"))
            assertEquals("project-a", unchanged.conversation.projectId)
            assertEquals(listOf("message-a"), unchanged.messages.map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun deletingSourceMessageRemovesOnlyItsProjectMemory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("project-a", "项目 A", "", 1L),
                ProjectEntity("project-b", "项目 B", "", 1L)
            ))
            val persistence = ConversationPersistenceService(database)
            persistence.persistConversationMessages(
                conversationId = "conversation-a",
                projectId = "project-a",
                messages = listOf(ChatMessage("message-a", "user", "原始偏好内容"))
            )
            val sourceMemory = memory().copy(
                content = "原始偏好内容",
                fingerprint = UserMemoryStore.fingerprint("preference|原始偏好内容")
            )
            assertTrue(dao.saveUserMemoryCandidateIfSourceValid(sourceMemory))
            val repository = UserMemoryRepository(database)
            assertTrue(repository.importOne("project-b", UserMemoryExportCodec.encode(sourceMemory)))
            assertEquals(1, repository.list("project-a").size)
            assertEquals(1, repository.list("project-b").size)

            persistence.persistConversationMessages(
                conversationId = "conversation-a",
                projectId = "project-a",
                messages = listOf(ChatMessage("message-b", "user", "新的消息内容"))
            )
            assertTrue("来源消息被删除后应清理原项目自动记忆", repository.list("project-a").isEmpty())
            assertEquals("导入到其他项目的独立副本不应被删除", 1, repository.list("project-b").size)
        } finally {
            database.close()
        }
    }

    @Test
    fun automaticCandidatesRequireAnExistingUserMessageInTheSameProject() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("project-a", "项目 A", "", 1L),
                ProjectEntity("project-b", "项目 B", "", 1L)
            ))
            dao.putConversation(ConversationEntity("conversation-a", "来源会话 A", 200L, "project-a"))
            dao.putConversation(ConversationEntity("conversation-b", "来源会话 B", 200L, "project-b"))
            dao.putMessages(listOf(
                MessageEntity("message-a", "conversation-a", 0, "user", "偏好清晰直接的表达方式"),
                MessageEntity("message-b", "conversation-b", 0, "user", "偏好清晰直接的表达方式")
            ))
            val repository = UserMemoryRepository(database)
            val candidate = listOf(
                MemoryCandidate("preference", "偏好清晰直接的表达方式", 0.95)
            )

            assertEquals(0, repository.saveCandidates("project-b", "conversation-a", "message-a", candidate))
            assertEquals(0, repository.saveCandidates("project-a", "conversation-a", "missing-message", candidate))
            assertEquals(0, repository.saveCandidates("project-a", "conversation-b", "message-b", candidate))
            assertEquals(0, repository.saveCandidates("missing-project", "conversation-a", "message-a", candidate))
            assertTrue(repository.list("project-a").isEmpty())
            assertTrue(repository.list("project-b").isEmpty())

            assertEquals(1, repository.saveCandidates("project-a", "conversation-a", "message-a", candidate))
            assertEquals(1, repository.list("project-a").size)
            assertTrue(repository.list("project-b").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun daoCandidateWriteValidatesSourceAndPersistsWithinTheTransactionalBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("project-a", "项目 A", "", 1L),
                ProjectEntity("project-b", "项目 B", "", 1L)
            ))
            dao.putConversation(ConversationEntity("conversation-a", "来源会话", 200L, "project-a"))
            dao.putMessages(listOf(
                MessageEntity("message-a", "conversation-a", 0, "user", "偏好清晰直接的表达方式"),
                MessageEntity("assistant-a", "conversation-a", 1, "assistant", "收到")
            ))
            val candidate = memory().copy(
                id = "transactional-memory",
                fingerprint = UserMemoryStore.fingerprint("preference|偏好清晰直接的表达方式")
            )

            assertFalse(dao.saveUserMemoryCandidateIfSourceValid(
                candidate.copy(sourceMessageId = "assistant-a")
            ))
            assertFalse(dao.saveUserMemoryCandidateIfSourceValid(
                candidate.copy(projectId = "project-b")
            ))
            assertTrue(dao.saveUserMemoryCandidateIfSourceValid(candidate))
            val stored = requireNotNull(dao.userMemoryByFingerprint(
                "project-a",
                candidate.fingerprint
            ))
            assertEquals("conversation-a", stored.sourceConversationId)
            assertEquals("message-a", stored.sourceMessageId)
            assertTrue(dao.userMemories("project-b").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun importRejectsInvalidPayloadWithoutChangingExistingProjectData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = UserMemoryRepository(database)
            val invalid = """{"format":"nestro-memory","version":999}"""
            assertTrue(runCatching { repository.importOne("project-b", invalid) }.isFailure)
            assertTrue(repository.list("project-b").isEmpty())
        } finally {
            database.close()
        }
    }
    @Test
    fun sharedMemoryIsVisibleAcrossProjectsOnlyAfterExplicitGrantAndRevocationIsImmediate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("project-a", "项目 A", "", 1L),
                ProjectEntity("project-b", "项目 B", "", 1L)
            ))
            dao.putConversation(ConversationEntity("conversation-a", "来源会话", 200L, "project-a"))
            dao.putMessages(listOf(
                MessageEntity("message-a", "conversation-a", 0, "user", "偏好清晰直接的表达方式")
            ))
            dao.putUserMemory(memory())
            val repository = UserMemoryRepository(database)

            assertTrue(repository.recall("project-b", "清晰直接的表达方式").memories.isEmpty())
            assertTrue(repository.setShareAcrossProjects("source-memory", "project-a", true))
            repository.saveCandidates(
                "project-a", "conversation-a", "message-a",
                listOf(MemoryCandidate("preference", "偏好清晰直接的表达方式", 0.95))
            )
            assertTrue("自动提取更新同一条记忆时不得撤销用户的共享授权", repository.list("project-a").single().shareAcrossProjects)
            assertTrue("共享记忆还必须满足目标项目对来源项目的读取授权", repository.recall("project-b", "清晰直接的表达方式").memories.isEmpty())
            assertEquals(
                "source-memory",
                repository.recall("project-b", "清晰直接的表达方式", setOf("project-a")).memories.single().id
            )
            assertTrue("共享记忆不应复制到目标项目列表", repository.list("project-b").isEmpty())

            assertTrue(repository.setShareAcrossProjects("source-memory", "project-a", false))
            assertTrue("撤销后目标项目立即无法召回", repository.recall("project-b", "清晰直接的表达方式", setOf("project-a")).memories.isEmpty())
            assertEquals(1, repository.list("project-a").size)
        } finally {
            database.close()
        }
    }

}
