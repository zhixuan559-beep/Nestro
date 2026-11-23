package app.nestro.data

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
        LocalHttpServer(200, responseBody) { method, headers, body ->
            receivedMethod.set(method)
            receivedAuthorization.set(headers["authorization"].orEmpty())
            receivedContentType.set(headers["content-type"].orEmpty())
            receivedAccept.set(headers["accept"].orEmpty())
            receivedBody.set(body)
        }.use { server ->
            val connection = ChatHttpTransport.open(server.baseUrl)
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
        }
    }

    @Test
    fun readsErrorBodyForNonSuccessfulHttpStatus() {
        val errorBody = """{"error":{"message":"rate limit reached"}}"""
        LocalHttpServer(429, errorBody).use { server ->
            val connection = ChatHttpTransport.open(server.baseUrl)
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
        }
    }
}

private class LocalHttpServer(
    private val status: Int,
    private val responseBody: String,
    private val onRequest: (String, Map<String, String>, String) -> Unit = { _, _, _ -> }
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = Thread {
        server.accept().use { socket ->
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var matched = 0
            val delimiter = byteArrayOf(13, 10, 13, 10)
            while (matched < delimiter.size) {
                val next = input.read()
                if (next < 0) break
                headerBytes.write(next)
                matched = when {
                    next == delimiter[matched].toInt() -> matched + 1
                    next == delimiter[0].toInt() -> 1
                    else -> 0
                }
            }
            val headerText = headerBytes.toString(Charsets.ISO_8859_1.name())
            val lines = headerText.split("\r\n")
            val method = lines.firstOrNull()?.substringBefore(' ') ?: ""
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else
                    line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bodyBytes, offset, length - offset)
                if (count < 0) break
                offset += count
            }
            onRequest(method, headers, bodyBytes.copyOf(offset).toString(Charsets.UTF_8))
            val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
            val reason = if (status == 200) "OK" else "Error"
            val responseHeaders = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${responseBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(responseHeaders.toByteArray(Charsets.ISO_8859_1))
                write(responseBytes)
                flush()
            }
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        server.close()
        worker.join(1_000)
    }
}
