package app.nestro.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMediaAttachmentResolverTest {
    @Test
    fun extractsDistinctUuidAssetMarkersOnly() {
        val first = "123e4567-e89b-12d3-a456-426614174000"
        val second = "123e4567-e89b-12d3-a456-426614174001"
        val content = """
            已附加素材：
            - asset_id=$first; name=一张图.png; kind=IMAGE
            - asset_id=$second; name=另一张图.jpg; kind=IMAGE
            - asset_id=$first; name=重复引用.png; kind=IMAGE
            - asset_id=not-a-uuid; name=无效条目
        """.trimIndent()
        assertEquals(listOf(first, second), ChatMediaAttachmentResolver.referencedAssetIds(content))
    }

    @Test
    fun ignoresContentWithoutAssetMarkers() {
        assertEquals(emptyList<String>(), ChatMediaAttachmentResolver.referencedAssetIds("普通聊天文本"))
    }
}
