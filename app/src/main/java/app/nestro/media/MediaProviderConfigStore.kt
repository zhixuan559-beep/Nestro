package app.nestro.media

import android.content.Context
import app.nestro.data.LocalVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * User-configured OpenAI-style media endpoint.
 * The transcription endpoint follows /audio/transcriptions; scene and audio-processing
 * endpoints are configurable because those APIs are provider-specific.
 */
data class MediaProviderConfig(
    val providerId: String = "openai-compatible-media",
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val transcriptionPath: String = "audio/transcriptions",
    val scenePath: String = "media/scenes",
    val audioProcessPath: String = "audio/process"
) {
    fun validate() {
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "媒体服务商标识无效" }
        require(displayName.isNotBlank() && displayName.length <= 80) { "服务商名称无效" }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.none { it.isISOControl() }) { "API Key 无效" }
        require(model.isNotBlank() && model.length <= 120 && model.none { it.isISOControl() }) { "模型名称无效" }
        val uri = URI(baseUrl.trim().trimEnd('/'))
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "媒体服务地址必须使用 HTTPS，且不能包含 URL 凭据" }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." }) { "媒体服务地址包含不允许的路径参数" }
        listOf(transcriptionPath, scenePath, audioProcessPath).forEach {
            require(it.matches(Regex("[A-Za-z0-9._~/-]{1,160}")) && it.split('/').none { part -> part == "." || part == ".." }) { "媒体 API 路径无效" }
        }
    }

    override fun toString(): String = "MediaProviderConfig(providerId=$providerId, displayName=$displayName, baseUrl=$baseUrl, apiKey=[REDACTED], model=$model)"
}

object MediaProviderConnectionTest {
    suspend fun run(config: MediaProviderConfig): String = withContext(Dispatchers.IO) {
        config.validate()
        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", org.json.JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", "Reply with OK only.")))
            .put("max_tokens", 8)
            .toString()
        val connection = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
            .openConnection() as? HttpURLConnection ?: error("无法建立媒体服务连接")
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            require(status in 200..299) { "连接测试失败（HTTP $status）" }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 64 * 1024) { "连接测试响应超过 64 KB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val message = JSONObject(body.toString(Charsets.UTF_8))
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            require(message != null) { "服务已响应，但返回格式不符合 OpenAI Chat Completions" }
            "连接成功，模型已响应。"
        } finally {
            connection.disconnect()
        }
    }
}

class MediaProviderConfigStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nestro_media_provider_v1", Context.MODE_PRIVATE)

    fun save(config: MediaProviderConfig) {
        config.validate()
        val oldKey = runCatching { JSONObject(prefs.getString(METADATA, null) ?: "{}").optString("keyId") }.getOrNull()
        val nextKey = "media-provider-${UUID.randomUUID()}"
        LocalVault.writeApiKey(app, nextKey, config.apiKey)
        val metadata = JSONObject()
            .put("providerId", config.providerId)
            .put("displayName", config.displayName.trim())
            .put("baseUrl", config.baseUrl.trim().trimEnd('/'))
            .put("model", config.model.trim())
            .put("transcriptionPath", config.transcriptionPath.trim())
            .put("scenePath", config.scenePath.trim())
            .put("audioProcessPath", config.audioProcessPath.trim())
            .put("keyId", nextKey)
        if (!prefs.edit().putString(METADATA, metadata.toString()).commit()) {
            LocalVault.deleteApiKey(app, nextKey)
            error("媒体服务配置保存失败")
        }
        oldKey?.takeIf { it != nextKey }?.let { runCatching { LocalVault.deleteApiKey(app, it) } }
    }

    fun read(): MediaProviderConfig? {
        val raw = prefs.getString(METADATA, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val keyId = json.optString("keyId")
        if (!keyId.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) return null
        val key = runCatching { LocalVault.readApiKey(app, keyId) }.getOrDefault("")
        if (key.isBlank()) return null
        return runCatching {
            MediaProviderConfig(
                providerId = json.optString("providerId"),
                displayName = json.optString("displayName"),
                baseUrl = json.optString("baseUrl"),
                apiKey = key,
                model = json.optString("model"),
                transcriptionPath = json.optString("transcriptionPath", "audio/transcriptions"),
                scenePath = json.optString("scenePath", "media/scenes"),
                audioProcessPath = json.optString("audioProcessPath", "audio/process")
            ).also { it.validate() }
        }.getOrNull()
    }

    fun delete() {
        val keyId = runCatching { JSONObject(prefs.getString(METADATA, null) ?: "{}").optString("keyId") }.getOrNull()
        check(prefs.edit().remove(METADATA).commit()) { "媒体服务配置删除失败" }
        keyId?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,120}")) }?.let { runCatching { LocalVault.deleteApiKey(app, it) } }
    }

    private companion object {
        const val METADATA = "provider_metadata"
    }
}

class OpenAiCompatibleMediaIntelligenceProvider(
    private val config: MediaProviderConfig
) : MediaIntelligenceProvider {
    override val providerId: String = config.providerId
    override val displayName: String = config.displayName
    override val requiresNetworkTransfer: Boolean = true
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override fun cancelActiveRequests() {
        activeConnections.forEach { runCatching { it.disconnect() } }
    }

    override val capabilities: Set<MediaIntelligenceCapability> = setOf(
        MediaIntelligenceCapability.TRANSCRIPTION,
        MediaIntelligenceCapability.SCENE_DETECTION,
        MediaIntelligenceCapability.AUDIO_PROCESSING,
        MediaIntelligenceCapability.IMAGE_UNDERSTANDING
    )

    override suspend fun transcribe(asset: MediaAsset, sourceFile: File, languageTag: String, onProgress: (Float) -> Unit): List<MediaSubtitleCue> =
        withContext(Dispatchers.IO) {
            require(asset.kind == MediaAssetKind.AUDIO || asset.kind == MediaAssetKind.VIDEO) { "当前素材不能转写" }
            onProgress(0.05f)
            val response = multipart(config.transcriptionPath, sourceFile, asset.mimeType, mapOf("model" to config.model, "language" to languageTag))
            onProgress(0.9f)
            val root = JSONObject(response.toString(Charsets.UTF_8))
            val segments = root.optJSONArray("segments")
            val cues = if (segments != null && segments.length() > 0) {
                (0 until segments.length()).mapNotNull { index ->
                    val row = segments.optJSONObject(index) ?: return@mapNotNull null
                    val start = (row.optDouble("start", 0.0) * 1000).toLong().coerceAtLeast(0)
                    val end = (row.optDouble("end", 0.0) * 1000).toLong().coerceAtLeast(start + 1)
                    row.optString("text").trim().takeIf { it.isNotBlank() }?.let { MediaSubtitleCue(startMs = start, endMs = end, text = it) }
                }
            } else {
                val text = root.optString("text").trim()
                require(text.isNotBlank()) { "服务商未返回转写文本" }
                listOf(MediaSubtitleCue(startMs = 0, endMs = asset.durationMs.coerceAtLeast(1), text = text))
            }
            onProgress(1f)
            cues
        }

    override suspend fun detectScenes(asset: MediaAsset, sourceFile: File, onProgress: (Float) -> Unit): List<MediaScene> =
        withContext(Dispatchers.IO) {
            require(asset.kind == MediaAssetKind.VIDEO) { "场景识别需要视频素材" }
            onProgress(0.05f)
            val response = multipart(config.scenePath, sourceFile, asset.mimeType, mapOf("model" to config.model))
            onProgress(0.9f)
            val rows = JSONObject(response.toString(Charsets.UTF_8)).optJSONArray("scenes") ?: error("服务商响应缺少 scenes 数组")
            require(rows.length() <= 500) { "场景数量超过限制" }
            val result = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val start = (row.optDouble("start", 0.0) * 1000).toLong().coerceAtLeast(0)
                val end = (row.optDouble("end", 0.0) * 1000).toLong().coerceAtLeast(start + 1)
                MediaScene(start, end, row.optString("title").take(120), row.optString("description").take(1000), row.optString("caption").take(300))
            }
            onProgress(1f)
            result
        }

    override suspend fun processAudio(asset: MediaAsset, sourceFile: File, operation: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(asset.kind == MediaAssetKind.AUDIO || asset.kind == MediaAssetKind.VIDEO) { "当前素材不含可处理音频" }
            require(operation.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "音频处理操作名称无效" }
            onProgress(0.05f)
            val bytes = multipart(config.audioProcessPath, sourceFile, asset.mimeType, mapOf("model" to config.model, "operation" to operation))
            onProgress(0.9f)
            require(bytes.isNotEmpty() && bytes.size <= MAX_RESPONSE_BYTES) { "服务商返回的音频为空或超过 100 MB" }
            val output = File.createTempFile("nestro-media-ai-", ".audio")
            output.outputStream().use { it.write(bytes) }
            onProgress(1f)
            output
        }

    override suspend fun analyzeImage(asset: MediaAsset, sourceFile: File, prompt: String, onProgress: (Float) -> Unit): String =
        withContext(Dispatchers.IO) {
            require(asset.kind == MediaAssetKind.IMAGE) { "图片理解需要图片素材" }
            require(sourceFile.isFile && sourceFile.length() in 1..MAX_IMAGE_BYTES) { "图片不存在或超过 20 MB" }
            require(prompt.isNotBlank() && prompt.length <= 2_000) { "图片分析指令为空或过长" }
            val mime = asset.mimeType.takeIf { it.matches(Regex("image/(jpeg|png|webp|gif)")) }
                ?: error("当前图片格式不支持远程视觉分析")
            val encoded = Base64.getEncoder().encodeToString(sourceFile.readBytes())
            val dataUrl = "data:$mime;base64,$encoded"
            val payload = JSONObject()
                .put("model", config.model)
                .put("messages", org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", org.json.JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl))))
                )
                .put("max_tokens", 1200)
                .toString()
            val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
            val connection = url.openConnection() as? HttpURLConnection ?: error("无法建立媒体服务连接")
            activeConnections.add(connection)
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                require(status in 200..299) { "图片理解请求失败（HTTP $status）" }
                val body = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= MAX_JSON_RESPONSE_BYTES) { "图片理解响应超过 2 MB" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                val message = JSONObject(body.toString(Charsets.UTF_8))
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: error("图片理解服务响应格式无效")
                val content = message.opt("content")
                val text = when (content) {
                    is String -> content.trim()
                    is org.json.JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }
                        .joinToString("\\n").trim()
                    else -> ""
                }
                require(text.isNotBlank()) { "图片理解服务未返回文本" }
                onProgress(1f)
                text.take(20_000)
            } finally {
                activeConnections.remove(connection)
                connection.disconnect()
            }
        }

    private fun multipart(path: String, file: File, mimeType: String, fields: Map<String, String>): ByteArray {
        require(file.isFile && file.length() in 1..MAX_UPLOAD_BYTES) { "媒体文件不存在或超过 100 MB 上传限制" }
        require(mimeType.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))) { "媒体 MIME 类型无效" }
        val boundary = "NestroMedia-${UUID.randomUUID()}"
        val url = URL(config.baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        val connection = url.openConnection() as? HttpURLConnection ?: error("无法建立媒体服务连接")
        activeConnections.add(connection)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setChunkedStreamingMode(64 * 1024)
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json, audio/*")
            connection.outputStream.use { out ->
                fields.forEach { (name, value) ->
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"media.bin\"\r\nContent-Type: $mimeType\r\n\r\n".toByteArray())
                file.inputStream().use { input -> input.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            require(status in 200..299) { "媒体服务请求失败（HTTP $status）" }
            val stream = if (connection.contentType.orEmpty().startsWith("audio/")) connection.inputStream else connection.inputStream
            val output = ByteArrayOutputStream()
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "媒体服务响应超过 100 MB" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 100 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
