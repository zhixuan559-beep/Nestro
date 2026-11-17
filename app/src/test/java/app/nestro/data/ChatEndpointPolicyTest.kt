package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatEndpointPolicyTest {
    @Test
    fun normalizesBaseUrlAndPreservesApiPath() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            ChatEndpointPolicy.completionUrl(" https://api.example.com/v1/ ").toString()
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            ChatEndpointPolicy.completionUrl("http://127.0.0.1:8080/v1").toString()
        )
    }

    @Test
    fun rejectsUnsupportedOrCredentialBearingEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatEndpointPolicy.completionUrl("ftp://api.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatEndpointPolicy.completionUrl("https://user:password@api.example.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatEndpointPolicy.completionUrl("https://api.example.com/v1?token=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatEndpointPolicy.completionUrl("")
        }
    }

    @Test
    fun extractsProviderErrorAndFallsBackToHttpStatus() {
        assertEquals(
            "rate limit reached",
            ChatEndpointPolicy.errorMessage(429, """{"error":{"message":"rate limit reached"}}""")
        )
        assertEquals("HTTP 502", ChatEndpointPolicy.errorMessage(502, "upstream unavailable"))
        assertEquals("HTTP 500", ChatEndpointPolicy.errorMessage(500, """{"error":{"message":""}}"""))
    }
}
