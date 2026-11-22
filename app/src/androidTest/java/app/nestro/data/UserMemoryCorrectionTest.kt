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
class UserMemoryCorrectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun memory(id: String, projectId: String, content: String, category: String = "preference") =
        UserMemoryEntity(
            id = id,
            projectId = projectId,
            category = category,
            content = content,
            fingerprint = UserMemoryStore.fingerprint(category + "|" + content.lowercase()),
            sourceConversationId = "source-conversation",
            sourceMessageId = "source-message",
            confidence = 0.95,
            createdAt = 10L,
            updatedAt = 10L
        )

    @Test
    fun manualMemoryCreationIsProjectScopedSafeAndDeduplicated() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.dao()
            dao.putProjects(listOf(ProjectEntity("project-a", "项目 A", "", 1L)))
            val content = "当前项目默认使用 Kotlin 和 Jetpack Compose"
            assertTrue(UserMemoryStore.saveManual(dao, "project-a", content, "project"))
            assertFalse(UserMemoryStore.saveManual(dao, "project-a", content, "project"))
            assertEquals(1, dao.userMemories("project-a").size)
            assertFalse(UserMemoryStore.isSafeContent("项目联系人手机号 13812345678"))
        } finally {
            db.close()
        }
    }

    @Test
    fun explicitCorrectionReplacesOnlyTheNamedMemoryInItsOwnerProject() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.dao()
            dao.putProjects(listOf(ProjectEntity("project-a", "项目 A", "", 1L)))
            val original = memory("memory-a", "project-a", "我偏好简洁直接的表达")
            dao.putUserMemory(original)

            val correctedContent = "我偏好清晰简洁且有具体结果的表达"
            val saved = UserMemoryStore.saveCandidates(
                dao = dao,
                projectId = "project-a",
                conversationId = "conversation-new",
                messageId = "message-new",
                candidates = listOf(
                    MemoryCandidate("preference", correctedContent, 0.95, supersedesId = "memory-a")
                )
            )

            val corrected = dao.userMemoryById("memory-a", "project-a")
            assertEquals(1, saved)
            assertEquals(correctedContent, corrected?.content)
            assertEquals(original.createdAt, corrected?.createdAt)
            assertEquals("conversation-new", corrected?.sourceConversationId)
            assertEquals(1, dao.userMemories("project-a").size)
        } finally {
            db.close()
        }
    }

    @Test
    fun correctionRejectsForeignIdsAndFingerprintConflictsWithoutCreatingNewMemory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.dao()
            dao.putProjects(listOf(
                ProjectEntity("project-a", "项目 A", "", 1L),
                ProjectEntity("project-b", "项目 B", "", 1L)
            ))
            val original = memory("memory-a", "project-a", "原有的表达偏好记录")
            val foreign = memory("memory-foreign", "project-b", "另一个项目的偏好记录")
            val conflictContent = "已经存在的目标偏好内容"
            val conflict = memory("memory-conflict", "project-a", conflictContent)
            dao.putUserMemory(original)
            dao.putUserMemory(foreign)
            dao.putUserMemory(conflict)

            val foreignAttempt = UserMemoryStore.saveCandidates(
                dao, "project-a", "conversation-a", "message-a",
                listOf(MemoryCandidate("preference", "更正外部项目的记忆", 0.95, supersedesId = "memory-foreign"))
            )
            val conflictAttempt = UserMemoryStore.saveCandidates(
                dao, "project-a", "conversation-a", "message-b",
                listOf(MemoryCandidate("preference", conflictContent, 0.95, supersedesId = "memory-a"))
            )

            assertEquals(0, foreignAttempt)
            assertEquals(0, conflictAttempt)
            assertEquals(original.content, dao.userMemoryById("memory-a", "project-a")?.content)
            assertEquals(3, dao.userMemories("project-a").size)
            assertTrue(dao.userMemoryById("memory-foreign", "project-b") != null)
            assertFalse(dao.userMemoryById("memory-foreign", "project-a") != null)
        } finally {
            db.close()
        }
    }
}
