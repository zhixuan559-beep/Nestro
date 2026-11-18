package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPreferencesStoreTest {
    @Test
    fun corruptConversationSnapshotIsRejectedWithoutBeingOverwritten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("conversation_snapshot_corruption_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("conversations_v1", "{broken")
                .putString("messages", "[]").commit()

            assertTrue(runCatching { ConversationPreferencesStore.loadConversations(prefs) }.isFailure)
            assertEquals("{broken", prefs.getString("conversations_v1", null))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun nonStringOrMalformedMessageVersionsAreRejectedInsteadOfDropped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("conversation_snapshot_versions_test", Context.MODE_PRIVATE)
        try {
            val message = JSONObject().put("id", "m1").put("role", "assistant").put("content", "回答")
                .put("versions", JSONArray().put("有效版本").put(7)).put("active_version", 0)
            val conversation = JSONObject().put("id", "c1").put("title", "测试")
                .put("updated_at", 1L).put("project_id", "default").put("messages", JSONArray().put(message))
            val raw = JSONArray().put(conversation).toString()
            prefs.edit().clear().putString("conversations_v1", raw).putString("messages", "[]").commit()

            assertTrue(runCatching { ConversationPreferencesStore.loadConversations(prefs) }.isFailure)
            assertEquals(raw, prefs.getString("conversations_v1", null))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun corruptLegacyMessagesAreNotConvertedIntoAnEmptyConversation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("legacy_messages_corruption_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("conversations_v1", "[]")
                .putString("messages", "{broken").commit()

            assertTrue(runCatching { ConversationPreferencesStore.loadConversations(prefs) }.isFailure)
            assertEquals("[]", prefs.getString("conversations_v1", null))
            assertEquals("{broken", prefs.getString("messages", null))
        } finally {
            prefs.edit().clear().commit()
        }
    }
    @Test
    fun legacySnapshotWritePreservesAllMessagesBeyondFiveHundred() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("conversation_snapshot_long_history_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            val messages = (0..500).map { index ->
                ChatMessage("snapshot-m$index", if (index % 2 == 0) "user" else "assistant", "消息-$index")
            }
            ConversationPreferencesStore.saveConversationList(
                prefs,
                listOf(ConversationRecord("snapshot-c1", "长会话", 1L, messages))
            )

            val saved = JSONArray(prefs.getString("conversations_v1", "[]"))
                .getJSONObject(0).getJSONArray("messages")
            assertEquals(501, saved.length())
            assertEquals("消息-0", saved.getJSONObject(0).getString("content"))
            assertEquals("消息-500", saved.getJSONObject(500).getString("content"))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun activeConversationUpdateDoesNotTrimHistoryToFiveHundredMessages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("conversation_update_long_history_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putBoolean("room_storage_initialized", true).commit()
            val messages = (0..500).map { index ->
                ChatMessage("active-m$index", if (index % 2 == 0) "user" else "assistant", "消息-$index")
            }
            val updated = ConversationPreferencesStore.saveConversationMessages(
                prefs, "active-c1", messages, "default",
                listOf(ConversationRecord("active-c1", "长会话", 1L, emptyList(), messagesLoaded = false))
            )
            assertEquals(501, updated.single().messages.size)
            assertEquals("消息-0", updated.single().messages.first().content)
            assertEquals("消息-500", updated.single().messages.last().content)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun legacySnapshotRejectsMoreThanTwoThousandMessagesInsteadOfDroppingHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("conversation_snapshot_limit_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            val messages = (0..2_000).map { index ->
                ChatMessage("limit-m$index", "user", "消息-$index")
            }
            assertTrue(runCatching {
                ConversationPreferencesStore.saveConversationList(
                    prefs,
                    listOf(ConversationRecord("limit-c1", "超限", 1L, messages))
                )
            }.isFailure)
            assertEquals(null, prefs.getString("conversations_v1", null))
        } finally {
            prefs.edit().clear().commit()
        }
    }

}
