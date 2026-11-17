package app.nestro.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHttpTransportTest {
    @Test
    fun postsJsonWithBearerAndStreamingHeadersAndReadsSuccessfulBody() {
        val receivedMethod = AtomicReference("")
        val receivedAuthorization = AtomicReference("")
        val receivedContentType = AtomicReference("")
        val receivedAccept = AtomicReference("")
        val receivedBody = AtomicReference("")
        val responseBody = """{"choices":[{"message":{"content":"ok"}}]}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            receivedMethod.set(exchange.requestMethod)
            receivedAuthorization.set(exchange.requestHeaders.getFirst("Authorization").orEmpty())
            receivedContentType.set(exchange.requestHeaders.getFirst("Content-Type").orEmpty())
            receivedAccept.set(exchange.requestHeaders.getFirst("Accept").orEmpty())
            receivedBody.set(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
            val connection = ChatHttpTransport.open(baseUrl)
            ChatHttpTransport.configure(connection, "test-key", 1_000, 2_000, streaming = true)
            try {
                val status = ChatHttpTransport.postJson(connection, """{"model":"test","stream":true}""")
                assertEquals(200, status)
                assertEquals(responseBody, ChatHttpTransport.readBody(connection, status))
                assertEquals("POST", receivedMethod.get())
                assertEquals("Bearer test-key", receivedAuthorization.get())
                assertEquals("application/json", receivedContentType.get())
                assertEquals("text/event-stream", receivedAccept.get())
                assertTrue(receivedBody.get().contains("\"model\":\"test\""))
                assertEquals(5_000, connection.connectTimeout)
                assertEquals(5_000, connection.readTimeout)
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun readsErrorBodyForNonSuccessfulHttpStatus() {
        val errorBody = """{"error":{"message":"rate limit reached"}}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val bytes = errorBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(429, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val connection = ChatHttpTransport.open("http://127.0.0.1:${server.address.port}/v1")
            ChatHttpTransport.configure(connection, "test-key", 10_000, 10_000, streaming = false)
            try {
                val status = ChatHttpTransport.postJson(connection, """{"model":"test","stream":false}""")
                assertEquals(429, status)
                val body = ChatHttpTransport.readBody(connection, status)
                assertEquals(errorBody, body)
                assertEquals("rate limit reached", ChatEndpointPolicy.errorMessage(status, body))
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop(0)
        }
    }
}
