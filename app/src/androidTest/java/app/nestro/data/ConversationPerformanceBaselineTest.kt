package app.nestro.data

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPerformanceBaselineTest {
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
    fun recordsPagedListAndContentSearchLatencyAtModerateHistoryScale() = runBlocking {
        val conversations = (0 until 250).map { index ->
            ConversationEntity(
                id = "performance-conversation-$index",
                title = "性能基线会话 $index",
                updatedAt = index.toLong(),
                projectId = "performance-project"
            )
        }
        val messages = conversations.flatMapIndexed { index, conversation ->
            (0 until 12).map { position ->
                MessageEntity(
                    id = "${conversation.id}-message-$position",
                    conversationId = conversation.id,
                    position = position,
                    role = if (position % 2 == 0) "user" else "assistant",
                    content = if (index == 123 && position == 5) {
                        "performance-search-marker " + "x".repeat(1_000)
                    } else {
                        "历史消息 $index / $position " + "内容".repeat(40)
                    }
                )
            }
        }
        database.withTransaction {
            conversations.forEach { dao.putConversation(it) }
            dao.putMessages(messages)
        }

        val pageStartedAt = SystemClock.elapsedRealtime()
        val page = dao.conversationListPage("performance-project", "", 30, 0)
        val pageElapsedMs = SystemClock.elapsedRealtime() - pageStartedAt

        val searchStartedAt = SystemClock.elapsedRealtime()
        val search = dao.conversationListPage("performance-project", "performance-search-marker", 30, 0)
        val searchElapsedMs = SystemClock.elapsedRealtime() - searchStartedAt

        println("NESTRO_PERF conversation_count=250 message_count=3000 page_ms=$pageElapsedMs search_ms=$searchElapsedMs")
        assertEquals(30, page.size)
        assertEquals("performance-conversation-249", page.first().id)
        assertEquals(listOf("performance-conversation-123"), search.map { it.id })
        assertEquals(3_000, messages.size)
    }
}
