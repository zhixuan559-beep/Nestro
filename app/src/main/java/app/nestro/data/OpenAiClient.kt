package app.nestro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.UUID

internal fun boundedRequestHistory(history: List<ChatMessage>, systemPrompt: String = "", maxTokens: Int = 2048): List<ChatMessage> {
    val source = history.takeLast(40)
    val bounded = ChatRequestPolicy.boundedHistory(
        source.map { ChatHistoryItem(it.role, it.content) },
        systemPrompt,
        outputReserveTokens = maxTokens
    )
    // boundedHistory may skip blank entries; never infer source positions from the result size.
    return bounded.mapIndexed { index, item ->
        ChatMessage("request-context-$index", item.role, item.content)
    }
}

private fun estimatedUsageInput(history: List<ChatMessage>, systemPrompt: String, maxTokens: Int?): Int {
    val bounded = boundedRequestHistory(history, systemPrompt, maxTokens ?: 2048)
    val text = buildString {
        append(systemPrompt)
        bounded.forEach { message -> append('\n').append(message.role).append(":").append(message.content) }
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
                        else onUsage(candidate, estimatedUsageInput(history, systemPrompt, maxTokens), estimatedUsageOutput(response))
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
        if (config.protocol != app.nestro.model.ModelApiProtocol.OPENAI_CHAT_COMPLETIONS) {
            return completeNative(config, history, systemPrompt, connectTimeoutMillis, readTimeoutMillis, maxTokens, onProviderUsage)
        }
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
        if (config.protocol != app.nestro.model.ModelApiProtocol.OPENAI_CHAT_COMPLETIONS) {
            return@withContext streamNative(config, history, systemPrompt, maxTokens, requestId, onProviderUsage, onChunk)
        }
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
    private fun nativeEndpoint(config: ModelConfig, streaming: Boolean): java.net.URL {
        val base = config.baseUrl.trimEnd('/')
        val path = when (config.protocol) {
            app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES -> "/v1/messages"
            app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT ->
                "/models/${config.model}:" + if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
            else -> error("不支持的原生协议")
        }
        return java.net.URL(base + path)
    }

    private fun nativeBody(
        config: ModelConfig,
        history: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int?
    ): JSONObject {
        val messages = boundedRequestHistory(history, systemPrompt, maxTokens ?: 2048)
        return when (config.protocol) {
            app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES -> JSONObject().apply {
                put("model", config.model)
                put("max_tokens", (maxTokens ?: 2048).coerceIn(1, 32768))
                if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    messages.filter { it.role == "user" || it.role == "assistant" }.forEach { message ->
                        put(JSONObject().put("role", message.role).put("content", message.content))
                    }
                })
                put("stream", false)
            }
            app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT -> JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                }
                put("contents", JSONArray().apply {
                    messages.filter { it.role == "user" || it.role == "assistant" }.forEach { message ->
                        put(JSONObject().put("role", if (message.role == "assistant") "model" else "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", message.content))))
                    }
                })
                put("generationConfig", JSONObject().put("maxOutputTokens", (maxTokens ?: 2048).coerceIn(1, 32768)))
            }
            else -> error("不支持的原生协议")
        }
    }

    private fun configureNative(
        connection: HttpURLConnection,
        config: ModelConfig,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        streaming: Boolean
    ) {
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMillis.coerceIn(5_000, 60_000)
        connection.readTimeout = readTimeoutMillis.coerceIn(5_000, 180_000)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", if (streaming) "text/event-stream" else "application/json")
        when (config.protocol) {
            app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES -> {
                connection.setRequestProperty("x-api-key", config.apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            }
            app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT ->
                connection.setRequestProperty("x-goog-api-key", config.apiKey)
            else -> error("不支持的原生协议")
        }
    }

    private fun completeNative(
        config: ModelConfig,
        history: List<ChatMessage>,
        systemPrompt: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxTokens: Int?,
        onProviderUsage: (ProviderTokenUsage?) -> Unit
    ): String {
        val connection = nativeEndpoint(config, false).openConnection() as HttpURLConnection
        configureNative(connection, config, connectTimeoutMillis, readTimeoutMillis, false)
        activeConnections.add(connection)
        try {
            connection.outputStream.use {
                it.write(nativeBody(config, history, systemPrompt, maxTokens).toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: " + nativeError(config, response))
            val payload = JSONObject(response)
            val text = when (config.protocol) {
                app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES ->
                    payload.optJSONArray("content")?.let { blocks ->
                        (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it)?.optString("text") }
                            .joinToString("")
                    }.orEmpty()
                app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT ->
                    payload.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.let { parts ->
                            (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                                .joinToString("")
                        }.orEmpty()
                else -> ""
            }
            val usage = payload.optJSONObject("usage")
            val geminiUsage = payload.optJSONObject("usageMetadata")
            val input = usage?.optInt("input_tokens", -1) ?: geminiUsage?.optInt("promptTokenCount", -1) ?: -1
            val output = usage?.optInt("output_tokens", -1) ?: geminiUsage?.optInt("candidatesTokenCount", -1) ?: -1
            onProviderUsage(if (input >= 0 && output >= 0) ProviderTokenUsage(input, output) else null)
            return text
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }

    private fun nativeError(config: ModelConfig, response: String): String = runCatching {
        val root = JSONObject(response)
        when (config.protocol) {
            app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES ->
                root.optJSONObject("error")?.optString("message")
            app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT ->
                root.optJSONObject("error")?.optString("message")
            else -> null
        }?.takeIf { it.isNotBlank() } ?: response.take(400)
    }.getOrDefault(response.take(400))

    private suspend fun streamNative(
        config: ModelConfig,
        history: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int?,
        requestId: String,
        onProviderUsage: (ProviderTokenUsage?) -> Unit,
        onChunk: suspend (String) -> Unit
    ): String {
        val connection = nativeEndpoint(config, true).openConnection() as HttpURLConnection
        configureNative(connection, config, 20_000, 180_000, true)
        activeConnections.add(connection)
        requestConnections[requestId] = connection
        val answer = StringBuilder()
        try {
            val body = nativeBody(config, history, systemPrompt, maxTokens).put("stream", true)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = (connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }).orEmpty()
                throw IllegalStateException("HTTP $code: " + nativeError(config, error))
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val delta = when (config.protocol) {
                        app.nestro.model.ModelApiProtocol.ANTHROPIC_MESSAGES ->
                            if (json.optString("type") == "content_block_delta") json.optJSONObject("delta")?.optString("text").orEmpty() else ""
                        app.nestro.model.ModelApiProtocol.GEMINI_GENERATE_CONTENT ->
                            json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                        else -> ""
                    }
                    if (delta.isNotEmpty()) {
                        answer.append(delta)
                        onChunk(delta)
                    }
                    val usage = json.optJSONObject("usage")
                    val geminiUsage = json.optJSONObject("usageMetadata")
                    val input = usage?.optInt("input_tokens", -1) ?: geminiUsage?.optInt("promptTokenCount", -1) ?: -1
                    val output = usage?.optInt("output_tokens", -1) ?: geminiUsage?.optInt("candidatesTokenCount", -1) ?: -1
                    if (input >= 0 && output >= 0) onProviderUsage(ProviderTokenUsage(input, output))
                }
            }
            return answer.toString()
        } finally {
            activeConnections.remove(connection)
            requestConnections.remove(requestId, connection)
            connection.disconnect()
        }
    }

}
