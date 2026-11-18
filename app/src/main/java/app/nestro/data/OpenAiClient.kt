package app.nestro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.UUID

private fun boundedRequestHistory(history: List<ChatMessage>, systemPrompt: String = "", maxTokens: Int = 2048): List<ChatMessage> {
    val source = history.takeLast(40)
    val bounded = ChatRequestPolicy.boundedHistory(source.map { ChatHistoryItem(it.role, it.content) }, systemPrompt, outputReserveTokens = maxTokens)
    return bounded.mapIndexed { index, item -> source[source.size - bounded.size + index].copy(content = item.content) }
}

private fun estimatedUsageInput(history: List<ChatMessage>, systemPrompt: String, maxTokens: Int?): Int {
    val bounded = boundedRequestHistory(history, systemPrompt, maxTokens ?: 2048)
    val text = buildString {
        append(systemPrompt)
        bounded.forEach { message -> append("\\n").append(message.role).append(":").append(message.content) }
    }
    return ChatRequestPolicy.estimateTokens(text).coerceAtMost(Int.MAX_VALUE / 4) * 4
}

private fun estimatedUsageOutput(response: String): Int =
    ChatRequestPolicy.estimateTokens(response).coerceAtMost(Int.MAX_VALUE / 4) * 4

private fun supportsOpenAiStreamUsage(config: ModelConfig): Boolean =
    runCatching { java.net.URI(config.baseUrl).host.equals("api.openai.com", ignoreCase = true) }.getOrDefault(false)
internal object OpenAiClient {
    private val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val requestConnections = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()

    fun cancel(requestId: String? = null) {
        val targets = if (requestId.isNullOrBlank()) activeConnections.toList() else listOfNotNull(requestConnections[requestId])
        targets.forEach { connection -> runCatching { connection.disconnect() } }
    }

    fun completeWithFallback(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        connectTimeoutMillis: Int = 20000,
        readTimeoutMillis: Int = 90000,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        onProviderUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> }
    ): String {
        require(configs.isNotEmpty()) { "没有可用的模型配置" }
        var lastError: Throwable? = null
        configs.distinctBy { "${it.baseUrl}|${it.model}|${it.apiKey}" }.forEach { candidate ->
            try {
                var providerUsage: ProviderTokenUsage? = null
                val response = complete(candidate, history, systemPrompt, connectTimeoutMillis, readTimeoutMillis, temperature, maxTokens) { providerUsage = it }
                if (providerUsage != null) onProviderUsage(candidate, providerUsage!!.inputTokens, providerUsage!!.outputTokens)
                else onUsage(candidate, estimatedUsageInput(history, systemPrompt, maxTokens), estimatedUsageOutput(response))
                return response
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
            }
        }
        throw IllegalStateException("主模型与备用模型均调用失败：${lastError?.message ?: "未知错误"}", lastError)
    }

    suspend fun streamWithFallback(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        onProviderUsage: (ModelConfig, Int, Int) -> Unit = { _, _, _ -> },
        requestId: String = UUID.randomUUID().toString(),
        timeoutMillis: Long = 180_000,
        shouldStop: () -> Boolean = { false },
        onChunk: suspend (String) -> Unit
    ): String {
        require(configs.isNotEmpty()) { "没有可用的模型配置" }
        var lastError: Throwable? = null
        return try {
            withTimeout(timeoutMillis.coerceIn(15_000, 300_000)) {
            configs.distinctBy { "${it.baseUrl}|${it.model}|${it.apiKey}" }.forEach { candidate ->
                if (shouldStop()) throw IllegalStateException("用户停止生成")
                var attempt = 0
                while (attempt < 2) {
                    if (shouldStop()) throw IllegalStateException("用户停止生成")
                    var emitted = false
                    try {
                        var providerUsage: ProviderTokenUsage? = null
                        val response = stream(candidate, history, systemPrompt, temperature, maxTokens, requestId, onProviderUsage = { providerUsage = it }) { chunk ->
                            emitted = true
                            onChunk(chunk)
                        }
                        if (providerUsage != null) onProviderUsage(candidate, providerUsage!!.inputTokens, providerUsage!!.outputTokens)
                        else onUsage(candidate, systemPrompt.length + history.sumOf { it.content.length }, response.length)
                        return@withTimeout response
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        if (shouldStop()) throw IllegalStateException("用户停止生成", error)
                        if (emitted) throw error
                        lastError = error
                        if (attempt == 0 && ChatRequestPolicy.shouldRetry(error)) {
                            attempt++
                            delay(600)
                            continue
                        }
                        break
                    }
                }
            }
            throw IllegalStateException("主模型与备用模型均调用失败：${lastError?.message ?: "未知错误"}", lastError)
            }
        } finally {
            cancel(requestId)
        }
    }

    fun complete(config: ModelConfig, history: List<ChatMessage>, systemPrompt: String, connectTimeoutMillis:Int=20000, readTimeoutMillis:Int=90000, temperature:Double?=null, maxTokens:Int?=null, onProviderUsage: (ProviderTokenUsage?) -> Unit = {}): String {
        val connection = ChatHttpTransport.open(config.baseUrl).also {
            ChatHttpTransport.configure(it, config.apiKey, connectTimeoutMillis, readTimeoutMillis, streaming = false)
        }
        activeConnections.add(connection)
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                config.reasoningEffort?.let { put("reasoning_effort", it) }
                if (temperature != null) put("temperature", temperature.coerceIn(0.0, 2.0))
                if (maxTokens != null) put("max_tokens", maxTokens.coerceIn(1, 32768))
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    boundedRequestHistory(history, systemPrompt, maxTokens ?: 2048).forEach { msg ->
                        put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                })
                put("stream", false)
            }
            val code = ChatHttpTransport.postJson(connection, body.toString())
            val response = ChatHttpTransport.readBody(connection, code)
            if (code !in 200..299) {
                throw IllegalStateException(ChatEndpointPolicy.errorMessage(code, response))
            }
            val payload = JSONObject(response)
            val usage = payload.optJSONObject("usage")
            val input = usage?.optInt("prompt_tokens", -1) ?: -1
            val output = usage?.optInt("completion_tokens", -1) ?: -1
            onProviderUsage(if (input >= 0 && output >= 0) ProviderTokenUsage(input, output) else null)
            return payload.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }

    suspend fun stream(config: ModelConfig, history: List<ChatMessage>, systemPrompt: String, temperature: Double? = null, maxTokens: Int? = null, requestId: String = UUID.randomUUID().toString(), onProviderUsage: (ProviderTokenUsage?) -> Unit = {}, onChunk: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
        val connection = ChatHttpTransport.open(config.baseUrl).also {
            ChatHttpTransport.configure(it, config.apiKey, 20_000, 90_000, streaming = true)
        }
        activeConnections.add(connection)
        requestConnections[requestId] = connection
        val answer = StringBuilder()
        val pending = StringBuilder()
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                config.reasoningEffort?.let { put("reasoning_effort", it) }
                if (temperature != null) put("temperature", temperature.coerceIn(0.0, 2.0))
                if (maxTokens != null) put("max_tokens", maxTokens.coerceIn(1, 32768))
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    boundedRequestHistory(history, systemPrompt, maxTokens ?: 2048).forEach { msg -> put(JSONObject().put("role", msg.role).put("content", msg.content)) }
                })
                if (supportsOpenAiStreamUsage(config)) put("stream_options", JSONObject().put("include_usage", true))
                put("stream", true)
            }
            val code = ChatHttpTransport.postJson(connection, body.toString())
            if (code !in 200..299) {
                val response = ChatHttpTransport.readBody(connection, code)
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $code: " + (detail?.takeIf { it.isNotBlank() } ?: response.take(300)))
            }
            val decoder = app.nestro.data.OpenAiSseDecoder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val delta = decoder.acceptLine(line)
                    if (!delta.isNullOrEmpty()) {
                        answer.append(delta)
                        pending.append(delta)
                        if (pending.length >= 24) {
                            val chunk = pending.toString()
                            pending.setLength(0)
                            onChunk(chunk)
                        }
                    }
                    if (decoder.isDone) break
                    // Compatibility with providers that return a single JSON object instead of SSE.
                    if (line.trimStart().startsWith("{")) {
                        val fallback = runCatching {
                            JSONObject(line).getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                        }.getOrNull()
                        if (!fallback.isNullOrEmpty()) {
                            answer.append(fallback)
                            pending.append(fallback)
                        }
                    }
                }
            }
            decoder.finish()?.let { delta ->
                answer.append(delta)
                pending.append(delta)
            }
            if (pending.isNotEmpty()) onChunk(pending.toString())
            onProviderUsage(decoder.providerUsage)
            answer.toString()
        } finally {
            activeConnections.remove(connection)
            requestConnections.remove(requestId, connection)
            connection.disconnect()
        }
    }
}
