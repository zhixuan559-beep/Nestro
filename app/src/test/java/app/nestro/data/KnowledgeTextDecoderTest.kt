package app.nestro.data

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTextDecoderTest {
    @Test
    fun decodesUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "中文知识库".toByteArray(Charsets.UTF_8)
        assertEquals("中文知识库", KnowledgeTextDecoder.decode(bytes))
    }

    @Test
    fun decodesUtf16LittleEndianBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "本地资料".toByteArray(Charsets.UTF_16LE)
        assertEquals("本地资料", KnowledgeTextDecoder.decode(bytes))
    }

    @Test
    fun decodesUtf16BigEndianBom() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "项目知识".toByteArray(Charsets.UTF_16BE)
        assertEquals("项目知识", KnowledgeTextDecoder.decode(bytes))
    }

    @Test
    fun fallsBackToGb18030ForLegacyChineseText() {
        val source = "骑手片区风险预警"
        val bytes = source.toByteArray(Charset.forName("GB18030"))
        assertEquals(source, KnowledgeTextDecoder.decode(bytes))
    }

    @Test
    fun rejectsMalformedUtfBomInsteadOfSilentlyFallingBack() {
        val malformed = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41.toByte())
        val failure = runCatching { KnowledgeTextDecoder.decode(malformed) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("文件的 UTF 编码标记与实际内容不匹配，无法安全导入。", failure?.message)
    }

    @Test
    fun prefersUtf8ForAsciiAndModernText() {
        val source = "Android 本地知识库"
        assertEquals(source, KnowledgeTextDecoder.decode(source.toByteArray(Charsets.UTF_8)))
    }
}
