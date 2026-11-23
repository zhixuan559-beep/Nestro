package app.nestro.media

import java.util.UUID
import kotlin.math.roundToLong

object MediaTimelinePolicy {
    const val MAX_PROJECT_DURATION_MS = 7_200_000L
    const val MAX_SUBTITLES = 10_000
    const val MAX_CLIPS_PER_TRACK = 2_000
    const val MAX_AUDIO_TRACKS = 8
    const val MIN_CLIP_SPEED = 0.25f
    const val MAX_CLIP_SPEED = 4f

    fun addTrack(timeline: MediaTimeline, kind: MediaTrackKind): MediaTimeline {
        val track = when (kind) {
            MediaTrackKind.VIDEO -> {
                require(timeline.tracks.none { it.kind == MediaTrackKind.VIDEO }) { "项目只能有一条主视频轨" }
                MediaTrack("video-main", "主视频轨", kind)
            }
            MediaTrackKind.AUDIO -> {
                val count = timeline.tracks.count { it.kind == MediaTrackKind.AUDIO }
                require(count < MAX_AUDIO_TRACKS) { "音频轨最多支持 $MAX_AUDIO_TRACKS 条" }
                MediaTrack("audio-${UUID.randomUUID()}", "音频轨 ${count + 1}", kind)
            }
        }
        return timeline.copy(tracks = timeline.tracks + track)
    }

    fun addClip(timeline: MediaTimeline, trackId: String, asset: MediaAsset): MediaTimeline {
        require(asset.projectId == timeline.projectId) { "不能跨项目引用媒体素材" }
        require(asset.durationMs > 0 && asset.durationMs <= MAX_PROJECT_DURATION_MS) { "素材时长无效或超出项目上限" }
        val track = timeline.tracks.firstOrNull { it.id == trackId } ?: error("时间线轨道不存在")
        require((track.kind == MediaTrackKind.AUDIO) == (asset.kind == MediaAssetKind.AUDIO)) { "音频素材只能添加到音频轨，视频和图片只能添加到主视频轨" }
        require(track.clips.size < MAX_CLIPS_PER_TRACK) { "单轨剪辑数量达到上限" }
        val start = track.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
        require(start + asset.durationMs <= MAX_PROJECT_DURATION_MS) { "项目时长超过 2 小时" }
        val clip = MediaClip(assetId = asset.id, timelineStartMs = start, sourceStartMs = 0, sourceEndMs = asset.durationMs)
        return timeline.copy(tracks = timeline.tracks.map { if (it.id == trackId) it.copy(clips = it.clips + clip) else it })
    }

    fun addClip(timeline: MediaTimeline, trackId: String, assetId: String, assets: List<MediaAsset>): MediaTimeline =
        addClip(timeline, trackId, assets.firstOrNull { it.id == assetId } ?: error("素材不存在"))

    fun splitClip(timeline: MediaTimeline, trackId: String, clipId: String, offsetMs: Long): MediaTimeline {
        val track = timeline.tracks.firstOrNull { it.id == trackId } ?: error("轨道不存在")
        val clip = track.clips.firstOrNull { it.id == clipId } ?: error("剪辑片段不存在")
        require(offsetMs in 1 until clip.durationMs) { "拆分位置必须位于片段内部" }
        require(clip.sourceDurationMs > 1L) { "源素材不足以拆分" }
        val sourceOffsetMs = (offsetMs.toDouble() * clip.speed.toDouble())
            .roundToLong().coerceIn(1L, clip.sourceDurationMs - 1L)
        val splitSourceMs = clip.sourceStartMs + sourceOffsetMs
        val first = clip.copy(sourceEndMs = splitSourceMs)
        val second = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = first.timelineEndMs,
            sourceStartMs = splitSourceMs
        )
        return timeline.copy(tracks = timeline.tracks.map { t -> if (t.id == trackId) t.copy(clips = t.clips.flatMap { if (it.id == clipId) listOf(first, second) else listOf(it) }) else t })
    }

    fun trimClip(timeline: MediaTimeline, trackId: String, clipId: String, sourceStartMs: Long, sourceEndMs: Long): MediaTimeline {
        val track = timeline.tracks.firstOrNull { it.id == trackId } ?: error("轨道不存在")
        val clip = track.clips.firstOrNull { it.id == clipId } ?: error("剪辑片段不存在")
        require(sourceStartMs >= 0 && sourceEndMs > sourceStartMs) { "剪辑源区间无效" }
        return timeline.copy(tracks = timeline.tracks.map { t -> if (t.id == trackId) t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(sourceStartMs = sourceStartMs, sourceEndMs = sourceEndMs) else it }) else t })
    }

    fun moveClip(timeline: MediaTimeline, trackId: String, clipId: String, newStartMs: Long): MediaTimeline {
        require(newStartMs >= 0) { "片段不能移动到负时间" }
        val track = timeline.tracks.firstOrNull { it.id == trackId } ?: error("轨道不存在")
        require(track.clips.any { it.id == clipId }) { "剪辑片段不存在" }
        return timeline.copy(tracks = timeline.tracks.map { t -> if (t.id == trackId) t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(timelineStartMs = newStartMs) else it }) else t })
    }

    fun setClipSpeed(timeline: MediaTimeline, trackId: String, clipId: String, speed: Float): MediaTimeline {
        require(speed.isFinite() && speed in MIN_CLIP_SPEED..MAX_CLIP_SPEED) {
            "片段速度必须在 0.25× 到 4× 之间"
        }
        val track = timeline.tracks.firstOrNull { it.id == trackId } ?: error("轨道不存在")
        require(track.clips.any { it.id == clipId }) { "剪辑片段不存在" }
        val updated = timeline.copy(tracks = timeline.tracks.map { current ->
            if (current.id == trackId) current.copy(clips = current.clips.map {
                if (it.id == clipId) it.copy(speed = speed) else it
            }) else current
        })
        val ordered = updated.tracks.first { it.id == trackId }.clips.sortedBy { it.timelineStartMs }
        ordered.zipWithNext().forEach { (left, right) ->
            require(left.timelineEndMs <= right.timelineStartMs) {
                "变速后片段与同轨其他片段重叠，请先移动后续片段"
            }
        }
        require(updated.durationMs <= MAX_PROJECT_DURATION_MS) { "变速后项目时长超过 2 小时" }
        return updated
    }

    fun removeClip(timeline: MediaTimeline, trackId: String, clipId: String): MediaTimeline {
        require(timeline.tracks.any { it.id == trackId }) { "轨道不存在" }
        return timeline.copy(tracks = timeline.tracks.map { t -> if (t.id == trackId) t.copy(clips = t.clips.filterNot { it.id == clipId }) else t })
    }

    fun removeTrack(timeline: MediaTimeline, trackId: String): MediaTimeline {
        val track = timeline.tracks.firstOrNull { it.id == trackId } ?: error("轨道不存在")
        require(track.kind == MediaTrackKind.AUDIO) { "主视频轨不能删除" }
        require(track.clips.isEmpty()) { "请先删除轨道内的剪辑片段" }
        return timeline.copy(tracks = timeline.tracks.filterNot { it.id == trackId })
    }

    fun validate(timeline: MediaTimeline, assets: List<MediaAsset>) {
        require(timeline.projectId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) { "项目 ID 无效" }
        require(timeline.tracks.map { it.id }.distinct().size == timeline.tracks.size) { "轨道 ID 重复" }
        require(timeline.tracks.count { it.kind == MediaTrackKind.VIDEO } == 1) { "必须且只能有一条主视频轨" }
        val audioTracks = timeline.tracks.filter { it.kind == MediaTrackKind.AUDIO }
        require(audioTracks.size <= MAX_AUDIO_TRACKS) { "音频轨数量超限" }
        val videoDurationMs = timeline.tracks.first { it.kind == MediaTrackKind.VIDEO }.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
        if (videoDurationMs > 0) {
            audioTracks.forEach { track ->
                val audioDurationMs = track.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
                require(audioDurationMs <= videoDurationMs) { "${track.name}不能超过主视频时长，请先裁剪或移动音频片段" }
            }
        }
        val byId = assets.associateBy { it.id }
        val allClips = timeline.tracks.flatMap { it.clips }
        require(allClips.map { it.id }.distinct().size == allClips.size) { "剪辑片段 ID 重复" }
        require(timeline.subtitles.map { it.id }.distinct().size == timeline.subtitles.size) { "字幕 ID 重复" }
        timeline.tracks.forEach { track ->
            require(track.volume.isFinite() && track.volume in 0f..2f) { "轨道音量必须在 0 到 200% 之间" }
            require(track.clips.size <= MAX_CLIPS_PER_TRACK) { "单轨剪辑数量超限" }
            val ordered = track.clips.sortedBy { it.timelineStartMs }
            ordered.forEachIndexed { index, clip ->
                val asset = byId[clip.assetId] ?: error("时间线引用了不存在的素材")
                require(asset.projectId == timeline.projectId) { "时间线引用了其他项目素材" }
                require((track.kind == MediaTrackKind.AUDIO) == (asset.kind == MediaAssetKind.AUDIO)) { "素材轨道类型不匹配" }
                require(clip.timelineStartMs >= 0 && clip.sourceStartMs >= 0 && clip.sourceEndMs > clip.sourceStartMs) { "剪辑时间范围无效" }
                require(clip.speed.isFinite() && clip.speed in MIN_CLIP_SPEED..MAX_CLIP_SPEED) { "片段速度必须在 0.25× 到 4× 之间" }
                require(clip.sourceEndMs <= asset.durationMs) { "剪辑超出源素材时长" }
                require(clip.timelineEndMs <= MAX_PROJECT_DURATION_MS) { "项目时长超过 2 小时" }
                if (index > 0) require(ordered[index - 1].timelineEndMs <= clip.timelineStartMs) { "同一轨道的剪辑片段不能重叠" }
            }
        }
        require(timeline.subtitles.size <= MAX_SUBTITLES) { "字幕数量超限" }
        timeline.subtitles.forEach { cue -> require(cue.startMs >= 0 && cue.endMs > cue.startMs && cue.endMs <= MAX_PROJECT_DURATION_MS && cue.text.isNotBlank() && cue.text.length <= 5_000) { "字幕时间或内容无效" } }
        require(timeline.durationMs <= MAX_PROJECT_DURATION_MS) { "项目时长超过 2 小时" }
    }
}
