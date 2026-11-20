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
            val repository = UserMemoryRepository(database)
            val exported = UserMemoryExportCodec.encode(memory())

            assertTrue(repository.importOne("project-b", exported))
            assertFalse("重复导入不得创建第二条记忆", repository.importOne("project-b", exported))
            assertEquals(listOf("source-memory"), repository.list("project-a").map { it.id })
            val imported = repository.list("project-b").single()
            assertEquals("project-b", imported.projectId)
            assertEquals("conversation-a", imported.sourceConversationId)
            assertEquals("message-a", imported.sourceMessageId)

            repository.edit(imported.id, "project-b", "表达风格保持清楚简洁", "workflow")
            val edited = repository.list("project-b").single()
            assertEquals("workflow", edited.category)
            assertEquals("表达风格保持清楚简洁", edited.content)
            assertEquals(listOf("source-memory"), repository.list("project-a").map { it.id })

            repository.delete(edited.id, "project-b")
            assertTrue(repository.list("project-b").isEmpty())
            assertEquals(listOf("source-memory"), repository.list("project-a").map { it.id })
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
}
