package app.nestro.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaSubtitleCodecTest {
    @Test fun parsesCommaAndDotMillisecondsAndMultilineText() {
        val cues = MediaSubtitleCodec.parseSrt("1\n00:00:01,250 --> 00:00:02,500\n第一行\n第二行\n\n2\n00:00:03.000 --> 00:00:04.000\nNext")
        assertEquals(2, cues.size)
        assertEquals(1_250L, cues[0].startMs)
        assertEquals("第一行\n第二行", cues[0].text)
    }

    @Test fun exportsStableSrtAndRoundTrips() {
        val cues = listOf(MediaSubtitleCue(startMs = 1_234, endMs = 2_345, text = "hello"))
        val encoded = MediaSubtitleCodec.exportSrt(cues)
        assertEquals("00:00:01,234 --> 00:00:02,345", encoded.lines()[1])
        assertEquals(cues.single().text, MediaSubtitleCodec.parseSrt(encoded).single().text)
    }

    @Test fun rejectsInvalidTimeRangesAndEmptyText() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaSubtitleCodec.parseSrt("1\n00:00:02,000 --> 00:00:01,000\ninvalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaSubtitleCodec.parseSrt("1\n00:00:01,000 --> 00:00:02,000\n")
        }
    }

    @Test fun rejectsMalformedNonSubtitleBlocksInsteadOfSilentlySkipping() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaSubtitleCodec.parseSrt("1\\nnot a timestamp\\ntext")
        }
    }

    @Test fun rejectsOversizedSubtitlePayload() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaSubtitleCodec.parseSrt("x".repeat(2_000_001))
        }
    }
}
