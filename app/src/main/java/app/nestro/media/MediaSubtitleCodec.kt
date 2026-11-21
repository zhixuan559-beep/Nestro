package app.nestro.media

object MediaSubtitleCodec {
    private val timestamp = Regex("^(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})$")

    fun parseSrt(content: String): List<MediaSubtitleCue> {
        require(content.length <= 2_000_000) { "字幕文件超过 2 MB" }
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
        require(blocks.size <= MediaTimelinePolicy.MAX_SUBTITLES) { "字幕条数超过限制" }
        val cues = blocks.mapNotNull { block ->
            val lines = block.lines()
            val timeIndex = lines.indexOfFirst { it.contains("-->") }
            require(timeIndex >= 0) { "字幕块缺少时间轴" }
            val parts = lines[timeIndex].split("-->", limit = 2)
            require(parts.size == 2) { "字幕时间格式无效" }
            val start = parseTime(parts[0].trim())
            val end = parseTime(parts[1].trim().split(Regex("\\s+")).first())
            val text = lines.drop(timeIndex + 1).joinToString("\n").trim()
            require(text.isNotBlank()) { "字幕内容不能为空" }
            MediaSubtitleCue(startMs = start, endMs = end, text = text)
        }
        require(cues.isNotEmpty()) { "未找到有效字幕条目" }
        require(cues.all { it.startMs >= 0 && it.endMs > it.startMs }) { "字幕时间区间无效" }
        require(cues.all { it.endMs <= MediaTimelinePolicy.MAX_PROJECT_DURATION_MS }) { "字幕时长超出限制" }
        return cues.sortedBy { it.startMs }
    }

    fun exportSrt(cues: List<MediaSubtitleCue>): String {
        require(cues.size <= MediaTimelinePolicy.MAX_SUBTITLES) { "字幕条数超过限制" }
        return cues.sortedBy { it.startMs }.mapIndexed { index, cue ->
            require(cue.startMs >= 0 && cue.endMs > cue.startMs && cue.text.isNotBlank() && cue.text.length <= 5_000) { "字幕条目无效" }
            (index + 1).toString() + "\n" + formatTime(cue.startMs) + " --> " + formatTime(cue.endMs) +
                "\n" + cue.text.replace("\r\n", "\n").replace('\r', '\n')
        }.joinToString("\n\n", postfix = if (cues.isEmpty()) "" else "\n")
    }

    private fun parseTime(value: String): Long {
        val match = timestamp.matchEntire(value) ?: error("字幕时间戳无效")
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val millis = match.groupValues[4].toLong()
        require(minutes < 60 && seconds < 60) { "字幕时间戳超出范围" }
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    private fun formatTime(value: Long): String {
        val total = value.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = total / 60_000 % 60
        val seconds = total / 1_000 % 60
        val millis = total % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
