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
            dao.putUserMemory(memory())
            val repository = UserMemoryRepository(database)

            assertTrue(repository.recall("project-b", "清晰直接的表达方式").memories.isEmpty())
            assertTrue(repository.setShareAcrossProjects("source-memory", "project-a", true))
            assertEquals("source-memory", repository.recall("project-b", "清晰直接的表达方式").memories.single().id)
            assertTrue("共享记忆不应复制到目标项目列表", repository.list("project-b").isEmpty())

            assertTrue(repository.setShareAcrossProjects("source-memory", "project-a", false))
            assertTrue("撤销后目标项目立即无法召回", repository.recall("project-b", "清晰直接的表达方式").memories.isEmpty())
            assertEquals(1, repository.list("project-a").size)
        } finally {
            database.close()
        }
    }

}
