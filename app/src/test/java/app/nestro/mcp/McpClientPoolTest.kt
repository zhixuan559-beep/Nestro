package app.nestro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientPoolTest {
    private class FakeClient(
        val endpoint: String,
        val credential: String
    ) : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount += 1
        }
    }

    @Test
    fun sameServerEndpointAndCredentialReuseOneClient() {
        val created = mutableListOf<FakeClient>()
        val pool = McpClientPool(
            readCredential = { "token-a" },
            createClient = { endpoint, credential ->
                FakeClient(endpoint, credential).also(created::add)
            }
        )

        val first = pool.get("server-1", "https://example.com/mcp")
        val second = pool.get("server-1", "https://example.com/mcp")

        assertSame(first, second)
        assertEquals(1, created.size)
        assertEquals(0, first.closeCount)
        pool.close()
        assertEquals(1, first.closeCount)
    }

    @Test
    fun endpointOrCredentialChangeReplacesAndClosesOldClient() {
        val created = mutableListOf<FakeClient>()
        var credential = "token-a"
        val pool = McpClientPool(
            readCredential = { credential },
            createClient = { endpoint, token ->
                FakeClient(endpoint, token).also(created::add)
            }
        )

        val first = pool.get("server-1", "https://one.example/mcp")
        val second = pool.get("server-1", "https://two.example/mcp")
        assertNotSame(first, second)
        assertEquals(1, first.closeCount)

        credential = "token-b"
        val third = pool.get("server-1", "https://two.example/mcp")
        assertNotSame(second, third)
        assertEquals(1, second.closeCount)
        assertEquals("token-b", third.credential)
        assertEquals(3, created.size)
    }

    @Test
    fun invalidateClosesCachedClientAndNextGetCreatesAnother() {
        val created = mutableListOf<FakeClient>()
        val pool = McpClientPool(
            readCredential = { "token-a" },
            createClient = { endpoint, credential ->
                FakeClient(endpoint, credential).also(created::add)
            }
        )

        val first = pool.get("server-1", "https://example.com/mcp")
        pool.invalidate("server-1")
        assertTrue(first.closeCount == 1)

        val second = pool.get("server-1", "https://example.com/mcp")
        assertFalse(first === second)
        assertEquals(2, created.size)
    }
}
