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
class ProjectSpaceLifecycleTest {
    @Test fun protectsDefaultAndActiveSpacesAndRemovesOnlyEmptyPlaceholders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("default", "默认空间", "", 1L),
                ProjectEntity("active", "当前空间", "", 2L),
                ProjectEntity("empty", "空空间", "", 3L)
            ))
            dao.putConversation(ConversationEntity("placeholder", "新对话", 4L, "empty"))

            assertFalse(dao.deleteProjectIfEmpty("default", "active"))
            assertFalse(dao.deleteProjectIfEmpty("active", "active"))
            assertFalse(dao.deleteProjectIfEmpty("missing", "active"))
            assertTrue(dao.deleteProjectIfEmpty("empty", "active"))
            assertEquals(listOf("active", "default"), dao.loadProjects().map { it.id }.sorted())
            assertTrue(dao.loadConversations().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test fun refusesDeletionWhenProjectContainsConversationKnowledgeMemoryOrAgentData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).build()
        try {
            val dao = database.dao()
            val ids = listOf("chat", "named", "knowledge", "memory", "agent")
            dao.putProjects(ids.mapIndexed { index, id -> ProjectEntity(id, id, "", index.toLong()) })
            dao.putConversation(ConversationEntity("chat-conversation", "聊天", 1L, "chat"))
            dao.putMessages(listOf(MessageEntity("message", "chat-conversation", 0, "user", "不可丢失", "[]", 0)))
            dao.putConversation(ConversationEntity("named-conversation", "我命名的空会话", 2L, "named"))
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity(
                "document", "knowledge", "资料.txt", "text/plain", 4L, 3L, "hash-knowledge"
            ))
            dao.putUserMemory(UserMemoryEntity(
                "memory-row", "memory", "preference", "保留的记忆", "fingerprint",
                "", "", 0.9, 4L, 4L
            ))
            dao.putPhoneAgentTask(PhoneAgentTaskEntity(
                "task", "agent", "", "保留的任务", "GENERAL", "planned", "[]", 0, 5L, 5L
            ))

            ids.forEach { id -> assertFalse("项目 $id 有数据，不得删除", dao.deleteProjectIfEmpty(id, "default")) }
            assertEquals(5, dao.loadProjects().size)
            assertEquals("不可丢失", dao.loadConversations().first { it.conversation.id == "chat-conversation" }.messages.single().content)
        } finally {
            database.close()
        }
    }

    @Test fun projectAndConversationDataSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "nestro_project_space_lifecycle.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, NestroDatabase::class.java, name)
                .addMigrations(*NestroDatabase.ALL_MIGRATIONS).build()
            first.dao().putProjects(listOf(ProjectEntity("persisted", "长期项目", "专属指令", 10L)))
            first.dao().putConversation(ConversationEntity("persisted-chat", "长期会话", 11L, "persisted"))
            first.dao().putMessages(listOf(MessageEntity("persisted-message", "persisted-chat", 0, "user", "持久化内容", "[]", 0)))
            first.close()

            val reopened = Room.databaseBuilder(context, NestroDatabase::class.java, name)
                .addMigrations(*NestroDatabase.ALL_MIGRATIONS).build()
            try {
                assertEquals("专属指令", reopened.dao().loadProjects().single { it.id == "persisted" }.instructions)
                assertEquals("持久化内容", reopened.dao().loadConversations().single().messages.single().content)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
