package app.nestro.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * OpenAI-compatible HTTPS speech endpoints. These providers are opt-in and send audio/text
 * only to the endpoint explicitly configured by the user.
 */
class OpenAiCompatibleSpeechOutputProvider(
    override val providerId: String = "openai-compatible"
) : SpeechOutputProvider {
    override suspend fun synthesize(text: String, config: SpeechProviderConfig): SpeechAudio =
        withContext(Dispatchers.IO) {
            config.validateForRemoteProvider()
            require(config.providerId == providerId) { "语音服务商标识不匹配" }
            val normalized = SpeechTextPolicy.normalize(text)
                ?: throw IllegalArgumentException("朗读文本为空或超过 4000 字符")
            val endpoint = SpeechHttpPolicy.endpoint(config.baseUrl, "audio/speech")
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "audio/mpeg, audio/*")
            }
            try {
                val payload = JSONObject()
                    .put("model", config.outputModel)
                    .put("input", normalized)
                    .put("voice", config.voice ?: "alloy")
                    .put("response_format", "mp3")
                    .toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException(SpeechHttpPolicy.readError(status))
                }
                val mime = connection.contentType?.substringBefore(';')?.takeIf { it.startsWith("audio/", ignoreCase = true) }
                    ?: throw IllegalStateException("语音服务返回的不是音频数据")
                val bytes = BufferedInputStream(connection.inputStream).use {
                    SpeechHttpPolicy.readBounded(it, SpeechHttpPolicy.MAX_AUDIO_BYTES)
                }
                require(bytes.isNotEmpty()) { "语音服务返回了空音频" }
                SpeechAudio(bytes, mime)
            } finally {
                connection.disconnect()
            }
        }
}

/** OpenAI-compatible /audio/transcriptions endpoint for an explicitly configured speech service. */
class OpenAiCompatibleSpeechInputProvider(
    override val providerId: String = "openai-compatible"
) : SpeechInputProvider {
    override suspend fun transcribe(
        audio: ByteArray,
        mimeType: String,
        config: SpeechProviderConfig
    ): SpeechTranscript = withContext(Dispatchers.IO) {
        config.validateForRemoteProvider()
        require(config.providerId == providerId) { "语音服务商标识不匹配" }
        require(audio.isNotEmpty() && audio.size <= SpeechHttpPolicy.MAX_INPUT_AUDIO_BYTES) { "录音为空或超过 25 MB" }
        require(mimeType.matches(Regex("audio/[a-zA-Z0-9.+-]{1,40}"))) { "录音格式无效" }
        val endpoint = SpeechHttpPolicy.endpoint(config.baseUrl, "audio/transcriptions")
        val boundary = "NestroSpeech-${UUID.randomUUID()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { output ->
                fun field(name: String, value: String) {
                    output.write("--$boundary\r\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.write("\r\n".toByteArray())
                }
                field("model", config.inputModel)
                output.write("--$boundary\r\n".toByteArray())
                val extension = when (mimeType.lowercase()) {
                    "audio/wav", "audio/x-wav" -> "wav"
                    "audio/mp4", "audio/m4a" -> "m4a"
                    "audio/ogg" -> "ogg"
                    "audio/flac" -> "flac"
                    else -> "webm"
                }
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"recording.$extension\"\r\n".toByteArray())
                output.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                output.write(audio)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException(SpeechHttpPolicy.readError(status))
            }
            val body = connection.inputStream.use { SpeechHttpPolicy.readBounded(it, SpeechHttpPolicy.MAX_RESPONSE_BYTES) }
            val transcript = JSONObject(String(body, Charsets.UTF_8)).optString("text").trim()
            require(transcript.isNotBlank()) { "语音服务未返回识别文本" }
            SpeechTranscript(transcript, true)
        } finally {
            connection.disconnect()
        }
    }
}

internal object SpeechHttpPolicy {
    fun endpoint(baseUrl: String, suffix: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://", ignoreCase = true)) { "语音服务必须使用 HTTPS" }
        require(suffix.matches(Regex("[a-zA-Z0-9/_-]{1,100}"))) { "语音服务路径无效" }
        return "$base/$suffix"
    }

    fun readError(status: Int): String {
        return "语音服务请求失败（HTTP $status）"
    }

    fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "响应大小限制必须大于零" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "语音服务响应超过大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    const val MAX_AUDIO_BYTES = 20 * 1024 * 1024
    const val MAX_INPUT_AUDIO_BYTES = 25 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 64 * 1024
}
