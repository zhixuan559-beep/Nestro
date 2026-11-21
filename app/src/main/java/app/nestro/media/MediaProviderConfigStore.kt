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
    val model: String = "",
    val transcriptionModel: String = model,
    val transcriptionPath: String = "audio/transcriptions",
    val scenePath: String = "media/scenes",
    val audioProcessPath: String = "audio/process",
    val videoGenerationModel: String = "",
    val videoSubmitPath: String = "video/generations",
    val videoStatusPath: String = "video/generations/{job_id}",
    val videoModelField: String = "model",
    val videoPromptField: String = "prompt",
    val videoAuthHeader: String = "Authorization",
    val videoAuthPrefix: String = "Bearer ",
    val videoJobIdField: String = "id",
    val videoStatusField: String = "status",
    val videoResultUrlField: String = "video_url"
) {
    fun validate() {
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "媒体服务商标识无效" }
        require(displayName.isNotBlank() && displayName.length <= 80) { "服务商名称无效" }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.none { it.isISOControl() }) { "API Key 无效" }
        require(model.isBlank() || (model.length <= 120 && model.none { it.isISOControl() })) { "视觉/聊天模型名称无效" }
        require(transcriptionModel.isBlank() || (transcriptionModel.length <= 120 && transcriptionModel.none { it.isISOControl() })) { "转写模型名称无效" }
        require(model.isNotBlank() || transcriptionModel.isNotBlank() || videoGenerationModel.isNotBlank()) {
            "至少配置一个聊天、转写或视频生成模型"
        }
        val uri = URI(baseUrl.trim().trimEnd('/'))
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "媒体服务地址必须使用 HTTPS，且不能包含 URL 凭据" }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." }) { "媒体服务地址包含不允许的路径参数" }
        require(transcriptionPath.matches(Regex("[A-Za-z0-9._~/-]{1,160}")) && transcriptionPath.split('/').none { it == "." || it == ".." }) { "转写 API 路径无效" }
        listOf(scenePath, audioProcessPath, videoSubmitPath).filter { it.isNotBlank() }.forEach {
            require(it.matches(Regex("[A-Za-z0-9._~/-]{1,160}")) && it.split('/').none { part -> part == "." || part == ".." }) { "媒体 API 路径无效" }
        }
        require(videoStatusPath.length in 1..200 && videoStatusPath.matches(Regex("[A-Za-z0-9._~/{}/-]+")) &&
            videoStatusPath.replace("{job_id}", "job-id").split('/').none { it == "." || it == ".." } &&
            videoStatusPath.count { it == '{' } == 1 && videoStatusPath.count { it == '}' } == 1 &&
            videoStatusPath.contains("{job_id}")) { "视频任务状态路径必须包含且只包含 {job_id}" }
        if (videoGenerationModel.isNotBlank()) require(videoGenerationModel.length <= 120 && videoGenerationModel.none { it.isISOControl() }) { "视频生成模型名称无效" }
        require(videoAuthHeader.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "视频 API 认证请求头名称无效" }
        require(videoAuthPrefix.length <= 40 && videoAuthPrefix.none { it.isISOControl() }) { "视频 API 认证前缀无效" }
        listOf(videoModelField, videoPromptField, videoJobIdField, videoStatusField, videoResultUrlField).forEach {
            require(it.matches(Regex("[A-Za-z0-9_.]{1,100}"))) { "视频任务响应字段无效" }
        }
    }

    override fun toString(): String = "MediaProviderConfig(providerId=$providerId, displayName=$displayName, baseUrl=$baseUrl, apiKey=[REDACTED], model=$model)"
}

object MediaProviderConnectionTest {
    suspend fun run(config: MediaProviderConfig): String = withContext(Dispatchers.IO) {
        config.validate()
        require(config.model.isNotBlank()) { "当前服务商未配置聊天/视觉模型，无法执行聊天接口连通测试" }
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

data class MediaProviderSummary(
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val videoGenerationModel: String = ""
)

class MediaProviderConfigStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nestro_media_provider_v1", Context.MODE_PRIVATE)

    fun save(config: MediaProviderConfig) {
        config.validate()
        val root = readRoot()
        val providers = root.optJSONObject(PROVIDERS) ?: JSONObject()
        val previous = providers.optJSONObject(config.providerId)
        val oldKey = previous?.optString("keyId")
        val nextKey = "media-provider-${UUID.randomUUID()}"
        LocalVault.writeApiKey(app, nextKey, config.apiKey)
        providers.put(config.providerId, metadata(config, nextKey))
        root.put(PROVIDERS, providers)
        if (root.optString(ACTIVE_ID).isBlank()) root.put(ACTIVE_ID, config.providerId)
        if (!prefs.edit().putString(METADATA, root.toString()).commit()) {
            LocalVault.deleteApiKey(app, nextKey)
            error("媒体服务配置保存失败")
        }
        oldKey?.takeIf { it.isNotBlank() && it != nextKey }?.let { runCatching { LocalVault.deleteApiKey(app, it) } }
    }

    fun list(): List<MediaProviderSummary> {
        val providers = readRoot().optJSONObject(PROVIDERS) ?: return emptyList()
        return providers.keys().asSequence().mapNotNull { id ->
            val row = providers.optJSONObject(id) ?: return@mapNotNull null
            MediaProviderSummary(
                id, row.optString("displayName"), row.optString("baseUrl"),
                row.optString("model"), row.optString("videoGenerationModel")
            )
        }.sortedBy { it.displayName }
    }

    fun activeProviderId(): String = readRoot().optString(ACTIVE_ID)

    fun activate(providerId: String) {
        val root = readRoot()
        require(root.optJSONObject(PROVIDERS)?.has(providerId) == true) { "媒体服务商配置不存在" }
        root.put(ACTIVE_ID, providerId)
        check(prefs.edit().putString(METADATA, root.toString()).commit()) { "媒体服务切换失败" }
    }

    fun read(providerId: String? = null): MediaProviderConfig? {
        val root = readRoot()
        val providers = root.optJSONObject(PROVIDERS) ?: return null
        val id = providerId?.takeIf { it.isNotBlank() } ?: root.optString(ACTIVE_ID)
        if (id.isBlank()) return null
        val json = providers.optJSONObject(id) ?: return null
        val keyId = json.optString("keyId")
        if (!keyId.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) return null
        val key = runCatching { LocalVault.readApiKey(app, keyId) }.getOrDefault("")
        if (key.isBlank()) return null
        return runCatching {
            MediaProviderConfig(
                providerId = id,
                displayName = json.optString("displayName"),
                baseUrl = json.optString("baseUrl"),
                apiKey = key,
                model = json.optString("model"),
                transcriptionModel = json.optString("transcriptionModel", json.optString("model")),
                transcriptionPath = json.optString("transcriptionPath", "audio/transcriptions"),
                scenePath = json.optString("scenePath", "media/scenes"),
                audioProcessPath = json.optString("audioProcessPath", "audio/process"),
                videoGenerationModel = json.optString("videoGenerationModel", ""),
                videoSubmitPath = json.optString("videoSubmitPath", "video/generations"),
                videoStatusPath = json.optString("videoStatusPath", "video/generations/{job_id}"),
                videoModelField = json.optString("videoModelField", "model"),
                videoPromptField = json.optString("videoPromptField", "prompt"),
                videoAuthHeader = json.optString("videoAuthHeader", "Authorization"),
                videoAuthPrefix = json.optString("videoAuthPrefix", "Bearer "),
                videoJobIdField = json.optString("videoJobIdField", "id"),
                videoStatusField = json.optString("videoStatusField", "status"),
                videoResultUrlField = json.optString("videoResultUrlField", "video_url")
            ).also { it.validate() }
        }.getOrNull()
    }

    fun delete(providerId: String? = null) {
        val root = readRoot()
        val providers = root.optJSONObject(PROVIDERS) ?: JSONObject()
        val id = providerId?.takeIf { it.isNotBlank() } ?: root.optString(ACTIVE_ID)
        if (id.isBlank()) return
        val keyId = providers.optJSONObject(id)?.optString("keyId")
        providers.remove(id)
        root.put(PROVIDERS, providers)
        if (root.optString(ACTIVE_ID) == id) {
            root.put(ACTIVE_ID, providers.keys().asSequence().firstOrNull().orEmpty())
        }
        check(prefs.edit().putString(METADATA, root.toString()).commit()) { "媒体服务配置删除失败" }
        keyId?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,120}")) }?.let { runCatching { LocalVault.deleteApiKey(app, it) } }
    }

    private fun readRoot(): JSONObject {
        val raw = prefs.getString(METADATA, null) ?: return JSONObject().put(PROVIDERS, JSONObject()).put(ACTIVE_ID, "")
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONObject().put(PROVIDERS, JSONObject()).put(ACTIVE_ID, "")
        if (parsed.has(PROVIDERS)) return parsed
        // Migrate the previous single-provider metadata format without losing its encrypted key.
        val legacyId = parsed.optString("providerId")
        if (legacyId.isNotBlank() && parsed.has("keyId")) {
            return JSONObject()
                .put(PROVIDERS, JSONObject().put(legacyId, parsed))
                .put(ACTIVE_ID, legacyId)
        }
        return JSONObject().put(PROVIDERS, JSONObject()).put(ACTIVE_ID, "")
    }

    private fun metadata(config: MediaProviderConfig, keyId: String) = JSONObject()
        .put("displayName", config.displayName.trim())
        .put("baseUrl", config.baseUrl.trim().trimEnd('/'))
        .put("model", config.model.trim())
        .put("transcriptionModel", config.transcriptionModel.trim())
        .put("transcriptionPath", config.transcriptionPath.trim())
        .put("scenePath", config.scenePath.trim())
        .put("audioProcessPath", config.audioProcessPath.trim())
        .put("videoGenerationModel", config.videoGenerationModel.trim())
        .put("videoSubmitPath", config.videoSubmitPath.trim())
        .put("videoStatusPath", config.videoStatusPath.trim())
        .put("videoModelField", config.videoModelField.trim())
        .put("videoPromptField", config.videoPromptField.trim())
        .put("videoAuthHeader", config.videoAuthHeader.trim())
        .put("videoAuthPrefix", config.videoAuthPrefix)
        .put("videoJobIdField", config.videoJobIdField.trim())
        .put("videoStatusField", config.videoStatusField.trim())
        .put("videoResultUrlField", config.videoResultUrlField.trim())
        .put("keyId", keyId)

    private companion object {
        const val METADATA = "provider_metadata"
        const val PROVIDERS = "providers"
        const val ACTIVE_ID = "activeProviderId"
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

    override val capabilities: Set<MediaIntelligenceCapability> = buildSet {
        if (config.transcriptionModel.isNotBlank()) add(MediaIntelligenceCapability.TRANSCRIPTION)
        if (config.model.isNotBlank()) add(MediaIntelligenceCapability.IMAGE_UNDERSTANDING)
        if (config.model.isNotBlank() && config.scenePath.isNotBlank() && config.scenePath != "media/scenes") add(MediaIntelligenceCapability.SCENE_DETECTION)
        if (config.model.isNotBlank() && config.audioProcessPath.isNotBlank() && config.audioProcessPath != "audio/process") add(MediaIntelligenceCapability.AUDIO_PROCESSING)
    }

    override suspend fun transcribe(asset: MediaAsset, sourceFile: File, languageTag: String, onProgress: (Float) -> Unit): List<MediaSubtitleCue> =
        withContext(Dispatchers.IO) {
            require(asset.kind == MediaAssetKind.AUDIO || asset.kind == MediaAssetKind.VIDEO) { "当前素材不能转写" }
            onProgress(0.05f)
            val transcriptionFields = when (config.transcriptionModel) {
                "gpt-transcribe" -> mapOf("model" to config.transcriptionModel, "languages[]" to languageTag.lowercase())
                "whisper-1" -> mapOf("model" to config.transcriptionModel, "language" to languageTag.substringBefore('-'), "response_format" to "verbose_json", "timestamp_granularities[]" to "segment")
                else -> mapOf("model" to config.transcriptionModel, "language" to languageTag.substringBefore('-'))
            }
            val response = multipart(config.transcriptionPath, sourceFile, asset.mimeType, transcriptionFields, onProgress = onProgress)
            onProgress(0.97f)
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
            require(config.scenePath.isNotBlank() && config.scenePath != "media/scenes") { "当前服务商没有配置真实场景识别接口" }
            onProgress(0.05f)
            val response = multipart(config.scenePath, sourceFile, asset.mimeType, mapOf("model" to config.model), onProgress = onProgress)
            onProgress(0.97f)
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
            require(config.audioProcessPath.isNotBlank() && config.audioProcessPath != "audio/process") { "当前服务商没有配置真实音频处理接口" }
            require(operation.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "音频处理操作名称无效" }
            onProgress(0.05f)
            val bytes = multipart(config.audioProcessPath, sourceFile, asset.mimeType, mapOf("model" to config.model, "operation" to operation), expectedContentTypePrefix = "audio/", onProgress = onProgress)
            onProgress(0.97f)
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
            onProgress(0.05f)
            val encoded = Base64.getEncoder().encodeToString(sourceFile.readBytes())
            onProgress(0.35f)
            val dataUrl = "data:$mime;base64,$encoded"
            val payload = JSONObject()
                .put("model", config.model)
                .put("messages", org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", org.json.JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl))))
                ))

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
                onProgress(0.85f)
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

    private fun multipart(
        path: String,
        file: File,
        mimeType: String,
        fields: Map<String, String>,
        expectedContentTypePrefix: String? = null,
        onProgress: (Float) -> Unit = {}
    ): ByteArray {
        val uploadLimit = if (URI(config.baseUrl).host.equals("api.openai.com", true)) OPENAI_MAX_UPLOAD_BYTES else MAX_UPLOAD_BYTES
        require(file.isFile && file.length() in 1..uploadLimit) { "媒体文件不存在或超过 ${uploadLimit / (1024 * 1024)} MB 上传限制" }
        require(mimeType.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))) { "媒体 MIME 类型无效" }
        val boundary = "NestroMedia-${UUID.randomUUID()}"
        val uploadName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "media.bin" }
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
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$uploadName\"\r\nContent-Type: $mimeType\r\n\r\n".toByteArray())
                var sent = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        sent += count
                        onProgress((0.1f + 0.58f * (sent.toFloat() / file.length().toFloat())).coerceIn(0.1f, 0.68f))
                    }
                }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            require(status in 200..299) { "媒体服务请求失败（HTTP $status）" }
            expectedContentTypePrefix?.let { prefix ->
                require(connection.contentType.orEmpty().substringBefore(';').startsWith(prefix, ignoreCase = true)) {
                    "媒体服务返回格式不正确，预期 $prefix"
                }
            }
            val stream = connection.inputStream
            val output = ByteArrayOutputStream()
            val responseLength = connection.contentLengthLong
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "媒体服务响应超过 100 MB" }
                    output.write(buffer, 0, count)
                    if (responseLength > 0) onProgress((0.7f + 0.25f * (output.size().toFloat() / responseLength.toFloat())).coerceIn(0.7f, 0.95f))
                }
            }
            onProgress(0.95f)
            return output.toByteArray()
        } finally {
            activeConnections.remove(connection)
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        private const val OPENAI_MAX_UPLOAD_BYTES = 25L * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 100 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
