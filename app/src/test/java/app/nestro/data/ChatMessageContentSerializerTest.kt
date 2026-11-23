package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.junit.Test

class ChatMessageContentSerializerTest {
    private fun message() = ChatMessage(
        id = "user-1",
        role = "user",
        content = "请描述图片",
        mediaParts = listOf(
            ChatMessageMediaPart(
                kind = "IMAGE",
                mimeType = "image/jpeg",
                dataBase64 = "aGVsbG8=",
                estimatedTokens = 1_024
            )
        )
    )

    @Test
    fun openAiUsesTextAndImageUrlContentParts() {
        val content = ChatMessageContentSerializer.openAiContent(message()) as JSONArray
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("请描述图片", content.getJSONObject(0).getString("text"))
        val image = content.getJSONObject(1)
        assertEquals("image_url", image.getString("type"))
        assertEquals("data:image/jpeg;base64,aGVsbG8=", image.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun anthropicUsesBase64ImageSourceBlock() {
        val content = ChatMessageContentSerializer.anthropicContent(message()) as JSONArray
        val image = content.getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/jpeg", image.getJSONObject("source").getString("media_type"))
        assertEquals("aGVsbG8=", image.getJSONObject("source").getString("data"))
    }

    @Test
    fun geminiUsesInlineDataPart() {
        val content = ChatMessageContentSerializer.geminiParts(message())
        assertEquals("请描述图片", content.getJSONObject(0).getString("text"))
        val image = content.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("image/jpeg", image.getString("mimeType"))
        assertEquals("aGVsbG8=", image.getString("data"))
        assertTrue(ChatMessageContentSerializer.geminiContent(message()).getJSONArray("parts").length() == 2)
    }

    @Test
    fun unsupportedMediaKindsAreNotSerializedAsImages() {
        val audio = message().copy(
            mediaParts = listOf(ChatMessageMediaPart("AUDIO", "audio/mpeg", "YXVkaW8=", 2_000))
        )
        assertEquals("请描述图片", ChatMessageContentSerializer.openAiContent(audio))
        assertEquals("请描述图片", ChatMessageContentSerializer.anthropicContent(audio))
        assertEquals(1, ChatMessageContentSerializer.geminiParts(audio).length())
    }
}
