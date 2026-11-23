package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

/** Provider-native serialization for ephemeral media parts resolved from project assets. */
internal object ChatMessageContentSerializer {
    private fun images(message: ChatMessage): List<ChatMessageMediaPart> =
        message.mediaParts.filter {
            it.kind == "IMAGE" && it.mimeType.startsWith("image/") && it.dataBase64.isNotBlank()
        }

    private fun openAiAudio(message: ChatMessage): List<Pair<ChatMessageMediaPart, String>> =
        message.mediaParts.mapNotNull { part ->
            val format = when {
                part.kind != "AUDIO" -> null
                part.mimeType == "audio/wav" || part.mimeType == "audio/x-wav" -> "wav"
                part.mimeType == "audio/mpeg" || part.mimeType == "audio/mp3" -> "mp3"
                else -> null
            }
            if (format == null || part.dataBase64.isBlank()) null else part to format
        }

    private fun geminiMedia(message: ChatMessage): List<ChatMessageMediaPart> =
        message.mediaParts.filter { part ->
            part.dataBase64.isNotBlank() && when (part.kind) {
                "IMAGE" -> part.mimeType.startsWith("image/")
                "AUDIO" -> part.mimeType.startsWith("audio/")
                "VIDEO" -> part.mimeType.startsWith("video/")
                else -> false
            }
        }

    fun openAiMessage(message: ChatMessage): JSONObject =
        JSONObject().put("role", message.role).put("content", openAiContent(message))

    fun openAiContent(message: ChatMessage): Any {
        val images = images(message)
        val audio = openAiAudio(message)
        if (images.isEmpty() && audio.isEmpty()) return message.content
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", message.content))
            images.forEach { part ->
                put(JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:${part.mimeType};base64,${part.dataBase64}")
                        .put("detail", "auto")
                ))
            }
            audio.forEach { (part, format) ->
                put(JSONObject().put("type", "input_audio").put(
                    "input_audio",
                    JSONObject().put("data", part.dataBase64).put("format", format)
                ))
            }
        }
    }

    fun anthropicMessage(message: ChatMessage): JSONObject =
        JSONObject().put("role", message.role).put("content", anthropicContent(message))

    fun anthropicContent(message: ChatMessage): Any {
        val images = images(message)
        if (images.isEmpty()) return message.content
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", message.content))
            images.forEach { part ->
                put(JSONObject()
                    .put("type", "image")
                    .put("source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", part.mimeType)
                        .put("data", part.dataBase64)))
            }
        }
    }

    fun geminiContent(message: ChatMessage): JSONObject =
        JSONObject()
            .put("role", if (message.role == "assistant") "model" else "user")
            .put("parts", geminiParts(message))

    fun geminiParts(message: ChatMessage): JSONArray = JSONArray().apply {
        put(JSONObject().put("text", message.content))
        geminiMedia(message).forEach { part ->
            put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", part.mimeType)
                .put("data", part.dataBase64)))
        }
    }
}
