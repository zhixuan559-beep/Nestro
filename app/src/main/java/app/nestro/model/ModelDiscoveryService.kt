package app.nestro.model

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches model IDs from OpenAI-compatible provider catalogs.
 * Native Anthropic and Gemini catalogs require their own API adapters and are rejected here.
 */
class ModelDiscoveryService {
    suspend fun discover(provider: ModelProvider, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先添加 API Key" }
        require(provider.protocol == ModelApiProtocol.OPENAI_CHAT_COMPLETIONS) {
            "该服务商使用原生协议，专用模型目录适配尚未接入；可先按服务商控制台提供的模型 ID 添加。"
        }
        val base = provider.baseUrl.trimEnd('/')
        require(base.startsWith("https://", ignoreCase = true)) { "模型服务地址必须使用 HTTPS" }
        val url = URL("$base/models")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) {
                runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "获取模型列表失败：HTTP $code"
            }
            val data = JSONObject(body).optJSONArray("data") ?: error("服务商返回的数据格式不包含 data 模型列表")
            (0 until data.length()).mapNotNull { index ->
                data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        } finally {
            connection.disconnect()
        }
    }
}
