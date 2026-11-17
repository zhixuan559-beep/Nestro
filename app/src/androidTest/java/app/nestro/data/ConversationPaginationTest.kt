package app.nestro.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPaginationTest {
    private lateinit var database: NestroDatabase
    private lateinit var dao: NestroDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun pagesProjectConversationsInStableNewestFirstOrder() = runBlocking {
        repeat(45) { index ->
            val id = "conversation-$index"
            dao.putConversation(ConversationEntity(id, "会话 $index", index.toLong(), "project-a"))
            dao.putMessages(listOf(MessageEntity("message-$index", id, 0, "user", "内容 $index")))
        }

        val first = dao.conversationListPage("project-a", "", 20, 0)
        val second = dao.conversationListPage("project-a", "", 20, 20)
        val third = dao.conversationListPage("project-a", "", 20, 40)

        assertEquals(20, first.size)
        assertEquals(20, second.size)
        assertEquals(5, third.size)
        assertEquals("conversation-44", first.first().id)
        assertEquals("conversation-25", first.last().id)
        assertEquals("conversation-24", second.first().id)
        assertEquals("conversation-0", third.last().id)
        assertEquals(45, (first + second + third).map { it.id }.distinct().size)
    }

    @Test
    fun searchesTitleAndMessageContentWithoutLoadingFullConversationRows() = runBlocking {
        dao.putConversation(ConversationEntity("title-hit", "项目复盘", 2L, "project-a"))
        dao.putMessages(listOf(MessageEntity("m1", "title-hit", 0, "assistant", "普通摘要")))
        dao.putConversation(ConversationEntity("body-hit", "随手记录", 1L, "project-a"))
        dao.putMessages(listOf(MessageEntity("m2", "body-hit", 0, "user", "需要检索的目标关键词")))
        dao.putConversation(ConversationEntity("other-project", "项目复盘", 3L, "project-b"))

        val titleResults = dao.conversationListPage("project-a", "复盘", 30, 0)
        val bodyResults = dao.conversationListPage("project-a", "目标关键词", 30, 0)

        assertEquals(listOf("title-hit"), titleResults.map { it.id })
        assertEquals(listOf("body-hit"), bodyResults.map { it.id })
        assertEquals("需要检索的目标关键词", bodyResults.single().lastMessagePreview)
        assertEquals(1, bodyResults.single().messageCount)
        assertTrue(dao.conversationListPage("project-a", "不存在", 30, 0).isEmpty())
    }

    @Test
    fun loadsFullMessageHistoryOnlyForTheSelectedConversation() = runBlocking {
        dao.putConversation(ConversationEntity("selected", "选中的会话", 1L, "project-a"))
        dao.putMessages((0 until 80).map { index ->
            MessageEntity("message-$index", "selected", index, "user", "消息 $index")
        })

        val selected = dao.conversationWithMessages("selected")
        val missing = dao.conversationWithMessages("missing")

        assertEquals(80, selected?.messages?.size)
        assertEquals("消息 79", selected?.messages?.maxByOrNull { it.position }?.content)
        assertEquals(null, missing)
    }
    @Test
    fun boundsConversationPreviewWithoutTruncatingStoredMessage() = runBlocking {
        val longContent = "x".repeat(2_000)
        dao.putConversation(ConversationEntity("long-preview", "长消息", 1L, "project-a"))
        dao.putMessages(listOf(MessageEntity("long-message", "long-preview", 0, "assistant", longContent)))

        val summary = dao.conversationListPage("project-a", "", 30, 0).single()

        assertEquals(240, summary.lastMessagePreview.length)
        assertEquals(longContent, dao.conversationWithMessages("long-preview")?.messages?.single()?.content)
    }

    @Test
    fun usesStableIdTieBreakerWhenConversationTimestampsMatch() = runBlocking {
        dao.putConversation(ConversationEntity("conversation-a", "A", 100L, "project-a"))
        dao.putConversation(ConversationEntity("conversation-c", "C", 100L, "project-a"))
        dao.putConversation(ConversationEntity("conversation-b", "B", 100L, "project-a"))

        val page = dao.conversationListPage("project-a", "", 30, 0)

        assertEquals(listOf("conversation-c", "conversation-b", "conversation-a"), page.map { it.id })
    }

}
