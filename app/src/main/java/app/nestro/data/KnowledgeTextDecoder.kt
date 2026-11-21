package app.nestro.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes common local document encodings without silently corrupting Chinese text.
 * UTF BOMs take precedence; otherwise UTF-8 is preferred and GB18030 is the
 * compatibility fallback for legacy Windows/Chinese documents.
 */
internal object KnowledgeTextDecoder {
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        val (charset, offset) = when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            else -> null to 0
        }

        if (charset != null) {
            return try {
                decodeStrict(bytes, offset, charset)
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("文件的 UTF 编码标记与实际内容不匹配，无法安全导入。")
            }
        }

        return try {
            decodeStrict(bytes, 0, Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            try {
                decodeStrict(bytes, 0, Charset.forName("GB18030"))
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("文件编码无法识别，请另存为 UTF-8 后导入。")
            }
        }
    }

    private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
}
