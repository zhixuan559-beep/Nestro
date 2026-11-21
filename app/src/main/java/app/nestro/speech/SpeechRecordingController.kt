package app.nestro.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Bounded 16 kHz mono PCM16 recording for an explicitly requested remote transcription. */
class SpeechRecordingController : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val pcm = ByteArrayOutputStream()
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var failure: String? = null

    fun start() {
        check(!closed.get()) { "录音控制器已关闭" }
        check(!finished.get()) { "本次录音已经结束" }
        check(running.compareAndSet(false, true)) { "录音已经开始" }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            error("当前设备不支持 16 kHz PCM 录音")
        }
        val input = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 2, READ_BUFFER_BYTES * 2)
            )
        } catch (error: Throwable) {
            running.set(false)
            throw error
        }
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            running.set(false)
            error("无法初始化麦克风录音")
        }
        recorder = input
        try {
            input.startRecording()
        } catch (error: Throwable) {
            close()
            throw IllegalStateException("无法启动录音：${error.message ?: "设备错误"}", error)
        }
        captureThread = Thread({
            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                while (running.get()) {
                    val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        synchronized(pcm) {
                            if (pcm.size() + count > MAX_PCM_BYTES) {
                                failure = "远程录音最长支持 120 秒"
                                running.set(false)
                            } else {
                                pcm.write(buffer, 0, count)
                            }
                        }
                    } else if (running.get()) {
                        failure = "麦克风录音失败（$count）"
                        running.set(false)
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) failure = error.message ?: "麦克风录音失败"
                running.set(false)
            }
        }, "NestroSpeechRecorder").apply {
            isDaemon = true
            start()
        }
    }

    fun stopAndGetWav(): ByteArray {
        check(!closed.get()) { "录音控制器已关闭" }
        check(finished.compareAndSet(false, true)) { "本次录音已经结束" }
        running.set(false)
        runCatching { recorder?.stop() }
        val thread = captureThread
        runCatching { thread?.join(1_500) }
        if (thread?.isAlive == true) {
            close()
            error("录音设备停止超时")
        }
        recorder?.release()
        recorder = null
        captureThread = null
        failure?.let { error(it) }
        val data = synchronized(pcm) { pcm.toByteArray() }
        require(data.isNotEmpty()) { "没有录到语音内容" }
        return wav(data)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { recorder?.stop() }
        runCatching { captureThread?.join(1_000) }
        runCatching { recorder?.release() }
        recorder = null
        captureThread = null
        synchronized(pcm) { pcm.reset() }
    }

    private fun wav(data: ByteArray): ByteArray = SpeechWavCodec.encode(data)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val READ_BUFFER_BYTES = 4_096
        const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * 120
    }
}

internal object SpeechWavCodec {
    const val SAMPLE_RATE = 16_000
    const val WAV_HEADER_BYTES = 44

    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "PCM16 数据必须非空且为偶数字节" }
        val header = java.nio.ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size + 36)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
