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
        require(base.scheme?.lowercase(Locale.ROOT) in setOf("https", "http")) { "模型 API 地址必须使用 HTTP 或 HTTPS" }
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
