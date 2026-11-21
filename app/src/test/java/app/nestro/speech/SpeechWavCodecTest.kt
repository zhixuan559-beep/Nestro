package app.nestro.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechWavCodecTest {
    @Test
    fun encodesMonoPcm16AsValidWav() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = SpeechWavCodec.encode(pcm)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(40, ByteBuffer.wrap(wav, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(wav.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals(16_000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(32_000, ByteBuffer.wrap(wav, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("data", String(wav.copyOfRange(36, 40), Charsets.US_ASCII))
        assertEquals(4, ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(pcm.toList(), wav.copyOfRange(44, 48).toList())
    }

    @Test
    fun rejectsEmptyOrMisalignedPcm16() {
        assertThrows(IllegalArgumentException::class.java) { SpeechWavCodec.encode(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { SpeechWavCodec.encode(byteArrayOf(1, 2, 3)) }
    }
}
