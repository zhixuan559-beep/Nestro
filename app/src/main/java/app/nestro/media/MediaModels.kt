package app.nestro.media

import java.util.UUID

enum class MediaAssetKind { VIDEO, AUDIO, IMAGE }
enum class MediaTrackKind { VIDEO, AUDIO }
enum class MediaRenderStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class MediaIntelligenceCapability { TRANSCRIPTION, SCENE_DETECTION, AUDIO_PROCESSING, IMAGE_UNDERSTANDING }

data class MediaAsset(
    val id: String,
    val projectId: String,
    val fileName: String,
    val displayName: String,
    val mimeType: String,
    val kind: MediaAssetKind,
    val byteSize: Long,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val sha256: String,
    val importedAt: Long
)

data class MediaSubtitleCue(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class MediaClip(
    val id: String = UUID.randomUUID().toString(),
    val assetId: String,
    val timelineStartMs: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val muted: Boolean = false
) {
    val durationMs: Long get() = sourceEndMs - sourceStartMs
    val timelineEndMs: Long get() = timelineStartMs + durationMs
}

data class MediaTrack(
    val id: String,
    val name: String,
    val kind: MediaTrackKind,
    val muted: Boolean = false,
    val volume: Float = 1f,
    val clips: List<MediaClip> = emptyList()
)

data class MediaTimeline(
    val projectId: String,
    val tracks: List<MediaTrack>,
    val subtitles: List<MediaSubtitleCue> = emptyList(),
    val revision: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val durationMs: Long get() = maxOf(
        tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndMs } ?: 0L,
        subtitles.maxOfOrNull { it.endMs } ?: 0L
    )

    companion object {
        fun empty(projectId: String) = MediaTimeline(
            projectId = projectId,
            tracks = listOf(
                MediaTrack("video-main", "主视频轨", MediaTrackKind.VIDEO),
                MediaTrack("audio-main", "音频轨", MediaTrackKind.AUDIO)
            )
        )
    }
}
