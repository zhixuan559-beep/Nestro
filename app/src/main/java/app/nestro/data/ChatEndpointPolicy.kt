package app.nestro.data

import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.util.Locale

internal object ChatEndpointPolicy {
    fun completionUrl(baseUrl: String): URL {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "模型 API 地址不能为空" }
        val base = URI(normalized)
        val scheme = base.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "模型 API 地址必须使用 HTTP 或 HTTPS" }
        require(!base.host.isNullOrBlank()) { "模型 API 地址缺少有效主机名" }
        require(base.userInfo == null) { "模型 API 地址不能嵌入账号或密码" }
        require(base.query == null && base.fragment == null) { "模型 API 地址不能包含查询参数或片段" }
        return URI("$normalized/chat/completions").toURL()
    }

    fun errorMessage(statusCode: Int, responseBody: String): String {
        val detail = runCatching {
            JSONObject(responseBody).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return detail?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode"
    }
}


/**
 * Small HTTP boundary shared by completion and streaming requests.
 * Kept separate from Compose so transport behavior can be exercised with a local test server.
 */
internal object ChatHttpTransport {
    fun open(
        baseUrl: String,
        apiKey: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        streaming: Boolean
    ): java.net.HttpURLConnection {
        return ChatEndpointPolicy.completionUrl(baseUrl).openConnection()
            as java.net.HttpURLConnection
    }

    fun configure(
        connection: java.net.HttpURLConnection,
        apiKey: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        streaming: Boolean
    ) {
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMillis.coerceIn(5_000, 60_000)
        connection.readTimeout = readTimeoutMillis.coerceIn(5_000, 120_000)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer " + apiKey)
        if (streaming) connection.setRequestProperty("Accept", "text/event-stream")
    }

    fun postJson(connection: java.net.HttpURLConnection, body: String): Int {
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection.responseCode
    }

    fun readBody(connection: java.net.HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }
}
