package app.nestro.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class SpeechHttpPolicyTest {
    @Test
    fun endpointAppendsPathToVersionedBaseUrl() {
        assertEquals(
            "https://speech.example.com/v1/audio/speech",
            SpeechHttpPolicy.endpoint("https://speech.example.com/v1/", "audio/speech")
        )
    }

    @Test
    fun endpointRejectsInsecureBaseUrlAndUnsafeSuffix() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechHttpPolicy.endpoint("http://speech.example.com/v1", "audio/speech")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechHttpPolicy.endpoint("https://speech.example.com/v1", "../secrets")
        }
    }

    @Test
    fun boundedReaderAcceptsLimitAndRejectsOversizeResponse() {
        assertEquals(
            "audio",
            String(SpeechHttpPolicy.readBounded(ByteArrayInputStream("audio".toByteArray()), 5))
        )
        assertThrows(IllegalArgumentException::class.java) {
            SpeechHttpPolicy.readBounded(ByteArrayInputStream("audio!".toByteArray()), 5)
        }
    }

    @Test
    fun realtimeUrlUsesSecureWebSocketAndEncodesModel() {
        assertEquals(
            "wss://speech.example.com/v1/realtime?model=voice+model",
            OpenAiRealtimeVoiceProvider.realtimeUrl("https://speech.example.com/v1", "voice model")
        )
    }

    @Test
    fun realtimeUrlRejectsQueryAndCredentialsInBaseUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiRealtimeVoiceProvider.realtimeUrl("https://user:pass@speech.example.com/v1", "model")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiRealtimeVoiceProvider.realtimeUrl("https://speech.example.com/v1?token=x", "model")
        }
    }
}
