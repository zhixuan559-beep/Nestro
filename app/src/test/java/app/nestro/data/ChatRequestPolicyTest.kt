package app.nestro.data
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
class ChatRequestPolicyTest {
 @Test fun trimsHistoryToBudget(){val h=(1..20).map{ChatHistoryItem(if(it%2==0)"assistant" else "user","x".repeat(5000))};val r=ChatRequestPolicy.boundedHistory(h,"system".repeat(1000),8192,1024);assertTrue(r.size<h.size);assertTrue(r.sumOf{it.content.length}<=48000);assertEquals(h.last().role,r.last().role)}
 @Test fun keepsAndTruncatesLatestOversizedMessage(){val r=ChatRequestPolicy.boundedHistory(listOf(ChatHistoryItem("user","x".repeat(100000))),"",4096,512);assertEquals(1,r.size);assertTrue(r.single().content.length<100000)}
 @Test fun retriesOnlyTransientErrors(){assertTrue(ChatRequestPolicy.shouldRetry(IOException("connection reset")));assertTrue(ChatRequestPolicy.shouldRetry(IllegalStateException("HTTP 429")));assertTrue(ChatRequestPolicy.shouldRetry(IllegalStateException("HTTP 503")));assertFalse(ChatRequestPolicy.shouldRetry(IllegalStateException("HTTP 401")));assertFalse(ChatRequestPolicy.shouldRetry(IllegalStateException("invalid request")))}
}
