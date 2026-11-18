package app.nestro.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRequestRecoveryPersistenceTest {
    @Test
    fun recoveredPartialAnswerIsCommittedToRoomBeforeRecoveryReturns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).build()
        val preferences = context.getSharedPreferences("chat_recovery_room_test", Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean("room_storage_initialized", true).apply()
        try {
            database.dao().putConversation(ConversationEntity("conversation-1", "新对话", 1L, "project-1"))
            database.dao().putMessages(
                listOf(MessageEntity("user-1", "conversation-1", 0, "user", "原始问题"))
            )
            preferences.edit().putString(
                "chat_request_state_v1",
                """{"status":"running","request_id":"request-1","conversation_id":"conversation-1","project_id":"project-1","prompt":"继续","partial_answer":"已生成部分"}"""
            ).apply()

            val persistence = ConversationPersistenceService(database)
            val coordinator = ChatRequestRecoveryCoordinator(persistence)
            coordinator.recover(
                preferences = preferences,
                conversations = listOf(ConversationRecord("conversation-1", "新对话", 1L, emptyList(), "project-1")),
                databaseReady = true,
                activeConversationId = "conversation-1"
            )

            val restored = persistence.loadMessages("conversation-1").orEmpty()
            assertEquals(listOf("原始问题", "已生成部分\n\n[请求中断，已保留已接收内容]"), restored.map { it.content })
            assertNull(preferences.getString("chat_request_state_v1", null))
        } finally {
            preferences.edit().clear().commit()
            database.close()
        }
    }

    @Test
    fun recoveryMarkerCannotWriteIntoConversationFromAnotherProject() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java).build()
        val preferences = context.getSharedPreferences("chat_recovery_project_test", Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean("room_storage_initialized", true).apply()
        try {
            database.dao().putConversation(ConversationEntity("conversation-2", "其他项目对话", 1L, "project-2"))
            database.dao().putMessages(
                listOf(MessageEntity("user-2", "conversation-2", 0, "user", "其他项目原文"))
            )
            preferences.edit().putString(
                "chat_request_state_v1",
                """{"status":"running","request_id":"request-2","conversation_id":"conversation-2","project_id":"project-1","prompt":"不应恢复","partial_answer":"不应写入"}"""
            ).apply()

            val coordinator = ChatRequestRecoveryCoordinator(ConversationPersistenceService(database))
            coordinator.recover(
                preferences = preferences,
                conversations = listOf(ConversationRecord("conversation-2", "其他项目对话", 1L, emptyList(), "project-2")),
                databaseReady = true,
                activeConversationId = "conversation-2"
            )

            val restored = database.dao().conversationWithMessages("conversation-2")!!.messages
            assertEquals(listOf("其他项目原文"), restored.map { it.content })
            assertNull(preferences.getString("draft_conversation-2", null))
            assertNull(preferences.getString("chat_request_state_v1", null))
        } finally {
            preferences.edit().clear().commit()
            database.close()
        }
    }
}
