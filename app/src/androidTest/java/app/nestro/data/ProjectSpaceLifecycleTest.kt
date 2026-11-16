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
            dao.putConversation(ConversationEntity("other-placeholder", "新对话", 5L, "active"))

            assertFalse(dao.deleteProjectIfEmpty("default", "active"))
            assertFalse(dao.deleteProjectIfEmpty("active", "active"))
            assertFalse(dao.deleteProjectIfEmpty("missing", "active"))
            assertTrue(dao.deleteProjectIfEmpty("empty", "active"))
            assertEquals(listOf("active", "default"), dao.loadProjects().map { it.id }.sorted())
            assertEquals(listOf("other-placeholder"), dao.loadConversations().map { it.conversation.id })
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
            dao.putConversation(ConversationEntity("empty-but-named", "项目备忘", 2L, "named"))
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

    @Test fun conversationsKnowledgeMemoriesAndAgentQueriesStayInsideTheirProject() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.putProjects(listOf(
                ProjectEntity("p1", "项目一", "只属于一", 1L),
                ProjectEntity("p2", "项目二", "只属于二", 2L)
            ))
            dao.putConversation(ConversationEntity("c1", "一的会话", 1L, "p1"))
            dao.putConversation(ConversationEntity("c2", "二的会话", 2L, "p2"))
            dao.putMessages(listOf(
                MessageEntity("m1", "c1", 0, "user", "项目一机密词"),
                MessageEntity("m2", "c2", 0, "user", "项目二机密词")
            ))
            assertEquals(listOf("m1"), dao.searchConversationMessages("p1", "", "机密词").map { it.messageId })
            assertEquals(listOf("m2"), dao.searchConversationMessages("p2", "", "机密词").map { it.messageId })

            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("d1", "p1", "一号资料", "text/plain", 1L, 1L, "hash-p1"))
            dao.insertKnowledgeDocument(KnowledgeDocumentEntity("d2", "p2", "二号资料", "text/plain", 1L, 2L, "hash-p2"))
            dao.insertKnowledgeChunks(listOf(
                KnowledgeChunkEntity("k1", "d1", "p1", 0, "项目一专属知识"),
                KnowledgeChunkEntity("k2", "d2", "p2", 0, "项目二专属知识")
            ))
            assertEquals(listOf("d1"), dao.knowledgeDocuments("p1").map { it.id })
            assertEquals(listOf("k1"), dao.searchKnowledgeChunks("p1", "专属", 8).map { it.id })
            assertEquals(listOf("d2"), dao.knowledgeDocuments("p2").map { it.id })
            assertEquals(listOf("k2"), dao.searchKnowledgeChunks("p2", "专属", 8).map { it.id })

            dao.putUserMemory(UserMemoryEntity("u1", "p1", "fact", "一的记忆", "fp1", "c1", "m1", 0.9, 1L, 1L))
            dao.putUserMemory(UserMemoryEntity("u2", "p2", "fact", "二的记忆", "fp2", "c2", "m2", 0.9, 2L, 2L))
            assertEquals(listOf("u1"), dao.userMemories("p1").map { it.id })
            assertEquals(listOf("u2"), dao.userMemories("p2").map { it.id })

            dao.putPhoneAgentTask(PhoneAgentTaskEntity("a1", "p1", "c1", "一的任务", "general", "planned", "[]", 0, 1L, 1L))
            dao.putPhoneAgentTask(PhoneAgentTaskEntity("a2", "p2", "c2", "二的任务", "general", "planned", "[]", 0, 2L, 2L))
            assertEquals(listOf("a1"), dao.phoneAgentTasks("p1").map { it.id })
            assertEquals(listOf("a2"), dao.phoneAgentTasks("p2").map { it.id })
        } finally {
            database.close()
        }
    }

    @Test fun pendingDeletionRecoveryRechecksDatabaseAndCleansOnlyEmptyOrphanPlaceholders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.putConversation(ConversationEntity("orphan-placeholder", "新对话", 1L, "deleted-project"))
            assertTrue(dao.recoverPendingProjectDeletionIfEmpty("deleted-project", "default"))
            assertTrue(dao.loadConversations().isEmpty())

            dao.putConversation(ConversationEntity("orphan-chat", "有效会话", 2L, "deleted-project"))
            dao.putMessages(listOf(MessageEntity("orphan-message", "orphan-chat", 0, "user", "不得清理")))
            assertFalse(dao.recoverPendingProjectDeletionIfEmpty("deleted-project", "default"))
            assertEquals("不得清理", dao.loadConversations().single().messages.single().content)

            assertFalse(dao.recoverPendingProjectDeletionIfEmpty("default", "other"))
            assertFalse(dao.recoverPendingProjectDeletionIfEmpty("other", "other"))
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
