package app.nestro.model

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retrieves provider-owned model catalogs using each provider's native catalog endpoint. */
class ModelDiscoveryService {
    suspend fun discover(provider: ModelProvider, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先添加 API Key / 访问令牌" }
        require(provider.baseUrl.startsWith("https://", ignoreCase = true)) { "模型服务地址必须使用 HTTPS" }
        val endpoint = when (provider.protocol) {
            ModelApiProtocol.OPENAI_CHAT_COMPLETIONS -> provider.baseUrl.trimEnd('/') + "/models"
            ModelApiProtocol.ANTHROPIC_MESSAGES -> provider.baseUrl.trimEnd('/') + "/v1/models"
            ModelApiProtocol.GEMINI_GENERATE_CONTENT -> provider.baseUrl.trimEnd('/') + "/models"
            ModelApiProtocol.CUSTOM -> error("自定义协议暂不支持自动发现，请手动添加模型 ID")
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            when (provider.protocol) {
                ModelApiProtocol.OPENAI_CHAT_COMPLETIONS -> setRequestProperty("Authorization", "Bearer $apiKey")
                ModelApiProtocol.ANTHROPIC_MESSAGES -> {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                ModelApiProtocol.GEMINI_GENERATE_CONTENT -> setRequestProperty("x-goog-api-key", apiKey)
                ModelApiProtocol.CUSTOM -> Unit
            }
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) {
                runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "获取模型列表失败：HTTP $code"
            }
            val root = JSONObject(body)
            val data = when (provider.protocol) {
                ModelApiProtocol.OPENAI_CHAT_COMPLETIONS -> root.optJSONArray("data")
                ModelApiProtocol.ANTHROPIC_MESSAGES -> root.optJSONArray("data")
                ModelApiProtocol.GEMINI_GENERATE_CONTENT -> root.optJSONArray("models")
                ModelApiProtocol.CUSTOM -> null
            } ?: error("服务商返回的数据格式不包含模型列表")
            (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val id = when (provider.protocol) {
                    ModelApiProtocol.GEMINI_GENERATE_CONTENT -> item.optString("name").removePrefix("models/")
                    else -> item.optString("id")
                }
                id.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        } finally {
            connection.disconnect()
        }
    }
}
