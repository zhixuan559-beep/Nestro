package app.nestro.data

/**
 * 语音消息标记：语音输入转写后发出的用户消息，在内容前加 `[NESTRO_VOICE:<毫秒>]` 前缀。
 * 展示层（MessageBubble）据此前缀画蓝色波形气泡；发给模型前由序列化层与请求策略剥掉，只留转写文本。
 * 前缀只出现在用户消息内容的最开头，持久化与 Room 均按普通文本存取。
 */
internal object VoiceMessageMarker {
    private val PREFIX = Regex("""^\[NESTRO_VOICE:(\d+)\]""")

    fun encode(durationMs: Long, transcript: String): String =
        "[NESTRO_VOICE:${durationMs.coerceAtLeast(0L)}]$transcript"

    /** 返回（录音时长毫秒，转写文本）；不是语音消息时返回 null。 */
    fun parse(content: String): Pair<Long, String>? {
        val match = PREFIX.find(content) ?: return null
        val durationMs = match.groupValues[1].toLongOrNull() ?: return null
        return durationMs to content.substring(match.range.last + 1)
    }

    /** 剥掉语音前缀，只留转写文本；不是语音消息时原样返回。 */
    fun strip(content: String): String = parse(content)?.second ?: content
}
