package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class ChatRequestStateStoreTest {
    @Test fun stateRoundTripsAndClearRemovesIt() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("chat_state_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ChatRequestStateStore.save(prefs,"r1","c1","p1","hello","partial",123L)
        val state = ChatRequestStateStore.load(prefs)
        assertEquals("r1",state?.requestId); assertEquals("partial",state?.partialAnswer); assertEquals(123L,state?.startedAt)
        ChatRequestStateStore.clear(prefs)
        assertNull(ChatRequestStateStore.load(prefs))
    }
}
