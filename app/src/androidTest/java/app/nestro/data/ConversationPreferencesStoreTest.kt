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
}
