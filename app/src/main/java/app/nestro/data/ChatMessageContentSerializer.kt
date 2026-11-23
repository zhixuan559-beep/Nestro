package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

/** Provider-native serialization for the ephemeral image parts resolved from project assets. */
internal object ChatMessageContentSerializer {
    private fun images(message: ChatMessage): List<ChatMessageMediaPart> =
        message.mediaParts.filter {
            it.kind == "IMAGE" && it.mimeType.startsWith("image/") && it.dataBase64.isNotBlank()
        }

    fun openAiMessage(message: ChatMessage): JSONObject =
        JSONObject().put("role", message.role).put("content", openAiContent(message))

    fun openAiContent(message: ChatMessage): Any {
        val images = images(message)
        if (images.isEmpty()) return message.content
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", message.content))
            images.forEach { part ->
                put(JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:${part.mimeType};base64,${part.dataBase64}")
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
        images(message).forEach { part ->
            put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", part.mimeType)
                .put("data", part.dataBase64)))
        }
    }
}
