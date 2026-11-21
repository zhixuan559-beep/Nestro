package app.nestro.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Streams 24 kHz mono PCM16 microphone frames to a realtime session and plays returned PCM16 frames.
 * The caller must obtain RECORD_AUDIO permission before start().
 */
class RealtimeVoiceAudioController(
    private val session: RealtimeVoiceSession,
    private val onError: (String) -> Unit = {}
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var playbackThread: Thread? = null
    private val playbackQueue = ArrayBlockingQueue<ByteArray>(16)

    fun start() {
        check(!closed.get()) { "实时音频控制器已关闭" }
        check(running.compareAndSet(false, true)) { "实时音频已经启动" }
        val minInput = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOutput = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minInput <= 0 || minOutput <= 0) {
            running.set(false)
            error("当前设备不支持 24 kHz PCM 音频")
        }
        val input = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minInput * 2, FRAME_BYTES * 2)
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
        val output = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minOutput * 2, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (error: Throwable) {
            input.release()
            running.set(false)
            throw error
        }
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            input.release()
            output.release()
            running.set(false)
            error("无法初始化语音播放设备")
        }
        recorder = input
        player = output
        try {
            output.play()
            input.startRecording()
        } catch (error: Throwable) {
            close()
            throw IllegalStateException("无法启动实时音频：${error.message ?: "设备错误"}", error)
        }
        playbackThread = Thread({
            try {
                while (running.get() || playbackQueue.isNotEmpty()) {
                    val chunk = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    var offset = 0
                    while (offset < chunk.size && running.get()) {
                        val written = output.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) throw IllegalStateException("实时音频播放失败（$written）")
                        offset += written
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (running.get()) onError(error.message ?: "实时音频播放失败")
                running.set(false)
            }
        }, "NestroRealtimeSpeaker").apply {
            isDaemon = true
            start()
        }
        captureThread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            try {
                while (running.get()) {
                    val count = input.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        session.sendAudio(frame.copyOf(count), "audio/pcm;rate=$SAMPLE_RATE")
                    } else if (count != AudioRecord.ERROR_INVALID_OPERATION &&
                        count != AudioRecord.ERROR_BAD_VALUE &&
                        count != AudioRecord.ERROR_DEAD_OBJECT
                    ) {
                        throw IllegalStateException("麦克风读取失败（$count）")
                    } else if (running.get()) {
                        throw IllegalStateException("麦克风录音设备已失效（$count）")
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) onError(error.message ?: "实时音频采集失败")
                running.set(false)
            }
        }, "NestroRealtimeMic").apply {
            isDaemon = true
            start()
        }
    }

    fun play(audio: SpeechAudio) {
        check(running.get()) { "实时音频尚未启动" }
        require(audio.mimeType.startsWith("audio/pcm", ignoreCase = true)) { "实时播放仅接受 PCM 音频" }
        require(audio.bytes.isNotEmpty() && audio.bytes.size <= MAX_PLAYBACK_CHUNK_BYTES && audio.bytes.size % 2 == 0) {
            "播放音频分片大小无效（PCM16 必须为偶数字节）"
        }
        check(player != null) { "语音播放设备尚未就绪" }
        check(playbackQueue.offer(audio.bytes.copyOf())) { "实时音频播放队列已满，请检查网络或设备性能" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { recorder?.stop() }
        playbackQueue.clear()
        runCatching { playbackThread?.interrupt() }
        if (Thread.currentThread() !== captureThread) runCatching { captureThread?.join(1_000) }
        if (Thread.currentThread() !== playbackThread) runCatching { playbackThread?.join(1_000) }
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.stop() }
        runCatching { recorder?.release() }
        runCatching { player?.release() }
        recorder = null
        player = null
        captureThread = null
        playbackThread = null
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val FRAME_BYTES = 4_800
        const val MAX_PLAYBACK_CHUNK_BYTES = 1 * 1024 * 1024
    }
}
