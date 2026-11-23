package app.nestro.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageContentSerializerTest {
    private fun message() = ChatMessage(
        id = "user-1",
        role = "user",
        content = "请理解这些素材",
        mediaParts = listOf(
            ChatMessageMediaPart("IMAGE", "image/jpeg", "aGVsbG8=", 1_024)
        )
    )

    @Test
    fun openAiUsesTextAndImageUrlContentParts() {
        val content = ChatMessageContentSerializer.openAiContent(message()) as JSONArray
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("请理解这些素材", content.getJSONObject(0).getString("text"))
        val image = content.getJSONObject(1)
        assertEquals("image_url", image.getString("type"))
        assertEquals("data:image/jpeg;base64,aGVsbG8=", image.getJSONObject("image_url").getString("url"))
        assertEquals("auto", image.getJSONObject("image_url").getString("detail"))
    }

    @Test
    fun openAiUsesInputAudioForSupportedWavAndMp3() {
        val audio = message().copy(mediaParts = listOf(
            ChatMessageMediaPart("AUDIO", "audio/wav", "d2F2", 2_048),
            ChatMessageMediaPart("AUDIO", "audio/mpeg", "bXAz", 2_048)
        ))
        val content = ChatMessageContentSerializer.openAiContent(audio) as JSONArray
        assertEquals("input_audio", content.getJSONObject(1).getString("type"))
        assertEquals("wav", content.getJSONObject(1).getJSONObject("input_audio").getString("format"))
        assertEquals("mp3", content.getJSONObject(2).getJSONObject("input_audio").getString("format"))
    }

    @Test
    fun anthropicUsesBase64ImageSourceBlockAndSkipsAudioVideo() {
        val mixed = message().copy(mediaParts = message().mediaParts + listOf(
            ChatMessageMediaPart("AUDIO", "audio/wav", "d2F2", 2_048),
            ChatMessageMediaPart("VIDEO", "video/mp4", "bXA0", 4_096)
        ))
        val content = ChatMessageContentSerializer.anthropicContent(mixed) as JSONArray
        assertEquals(2, content.length())
        val image = content.getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/jpeg", image.getJSONObject("source").getString("media_type"))
        assertEquals("aGVsbG8=", image.getJSONObject("source").getString("data"))
    }

    @Test
    fun geminiUsesInlineDataForImageAudioAndVideo() {
        val mixed = message().copy(mediaParts = message().mediaParts + listOf(
            ChatMessageMediaPart("AUDIO", "audio/mpeg", "bXAz", 2_048),
            ChatMessageMediaPart("VIDEO", "video/mp4", "bXA0", 4_096)
        ))
        val parts = ChatMessageContentSerializer.geminiParts(mixed)
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("audio/mpeg", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("video/mp4", parts.getJSONObject(2).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("请理解这些素材", parts.getJSONObject(3).getString("text"))
        assertTrue(ChatMessageContentSerializer.geminiContent(mixed).getJSONArray("parts").length() == 4)
    }

    @Test
    fun unsupportedOpenAiAudioFormatsAreNotSerialized() {
        val audio = message().copy(mediaParts = listOf(
            ChatMessageMediaPart("AUDIO", "audio/mp4", "bXA0", 2_048)
        ))
        assertEquals("请理解这些素材", ChatMessageContentSerializer.openAiContent(audio))
    }
}
