package app.nestro.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaTimelinePolicyTest {
    private val project = "project-a"
    private val video = MediaAsset(
        id = "video-asset", projectId = project, fileName = "video.mp4", displayName = "video.mp4",
        mimeType = "video/mp4", kind = MediaAssetKind.VIDEO, byteSize = 10, durationMs = 10_000,
        sha256 = "hash", importedAt = 1
    )
    private val audio = MediaAsset(
        id = "audio-asset", projectId = project, fileName = "audio.m4a", displayName = "audio.m4a",
        mimeType = "audio/mp4", kind = MediaAssetKind.AUDIO, byteSize = 10, durationMs = 8_000,
        sha256 = "hash", importedAt = 1
    )

    @Test
    fun addClipAppendsToTrackWithoutOverwritingExistingClip() {
        val initial = MediaTimeline.empty(project)
        val first = MediaTimelinePolicy.addClip(initial, "video-main", video)
        val second = MediaTimelinePolicy.addClip(first, "video-main", video)
        val clips = second.tracks.first { it.id == "video-main" }.clips
        assertEquals(2, clips.size)
        assertEquals(10_000L, clips[1].timelineStartMs)
        MediaTimelinePolicy.validate(second, listOf(video))
    }

    @Test
    fun splitKeepsSourceAndTimelineBoundariesContinuous() {
        val withClip = MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "video-main", video)
        val original = withClip.tracks.first { it.id == "video-main" }.clips.single()
        val split = MediaTimelinePolicy.splitClip(withClip, "video-main", original.id, 4_000)
        val clips = split.tracks.first { it.id == "video-main" }.clips.sortedBy { it.timelineStartMs }
        assertEquals(2, clips.size)
        assertEquals(clips[0].sourceEndMs, clips[1].sourceStartMs)
        assertEquals(clips[0].timelineEndMs, clips[1].timelineStartMs)
        MediaTimelinePolicy.validate(split, listOf(video))
    }

    @Test
    fun trimCanAdjustBothSourceEdgesAndRestoreWithinOriginalAssetBounds() {
        val withClip = MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "video-main", video)
        val original = withClip.tracks.first { it.id == "video-main" }.clips.single()
        val trimmed = MediaTimelinePolicy.trimClip(withClip, "video-main", original.id, 1_000, 9_000)
        assertEquals(1_000L, trimmed.tracks.first { it.id == "video-main" }.clips.single().sourceStartMs)
        val restored = MediaTimelinePolicy.trimClip(trimmed, "video-main", original.id, 0, 10_000)
        assertEquals(10_000L, restored.tracks.first { it.id == "video-main" }.clips.single().sourceEndMs)
        MediaTimelinePolicy.validate(restored, listOf(video))
    }

    @Test
    fun speedChangesOutputDurationAndSplitMapsBackToSourceTime() {
        val withClip = MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "video-main", video)
        val original = withClip.tracks.first { it.id == "video-main" }.clips.single()
        val sped = MediaTimelinePolicy.setClipSpeed(withClip, "video-main", original.id, 2f)
        val spedClip = sped.tracks.first { it.id == "video-main" }.clips.single()
        assertEquals(5_000L, spedClip.durationMs)

        val split = MediaTimelinePolicy.splitClip(sped, "video-main", original.id, 2_500L)
        val clips = split.tracks.first { it.id == "video-main" }.clips.sortedBy { it.timelineStartMs }
        assertEquals(2, clips.size)
        assertEquals(5_000L, clips[0].sourceEndMs)
        assertEquals(5_000L, clips[1].sourceStartMs)
        assertEquals(2_500L, clips[1].timelineStartMs)
        assertEquals(2_500L, clips[0].durationMs)
    }

    @Test
    fun speedIsBoundedAndCannotCreateSameTrackOverlap() {
        val withClip = MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "video-main", video)
        val original = withClip.tracks.first { it.id == "video-main" }.clips.single()
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.setClipSpeed(withClip, "video-main", original.id, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.setClipSpeed(withClip, "video-main", original.id, 5f)
        }
        val withTwo = MediaTimelinePolicy.addClip(withClip, "video-main", video)
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.setClipSpeed(withTwo, "video-main", original.id, 0.5f)
        }
    }

    @Test
    fun overlappingClipsAreRejected() {
        val first = MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "video-main", video)
        val second = MediaTimelinePolicy.addClip(first, "video-main", video)
        val clips = second.tracks.first { it.id == "video-main" }.clips
        val moved = MediaTimelinePolicy.moveClip(second, "video-main", clips[1].id, 5_000)
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.validate(moved, listOf(video))
        }
    }

    @Test
    fun audioMayBeAddedBeforeVideoButCannotExtendPastVideoOnceVideoExists() {
        val longAudio = audio.copy(durationMs = 12_000)
        val audioFirst = MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "audio-main", longAudio)
        MediaTimelinePolicy.validate(audioFirst, listOf(longAudio))
        val withVideo = MediaTimelinePolicy.addClip(audioFirst, "video-main", video)
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.validate(withVideo, listOf(longAudio, video))
        }
    }

    @Test
    fun projectCannotReferenceAnotherProjectsAsset() {
        val other = video.copy(projectId = "project-b")
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "video-main", other)
        }
    }

    @Test
    fun audioMustBePlacedOnAudioTrack() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.addClip(MediaTimeline.empty(project), "video-main", audio)
        }
    }

    @Test
    fun projectHasOnlyOneMainVideoTrack() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.addTrack(MediaTimeline.empty(project), MediaTrackKind.VIDEO)
        }
    }

    @Test
    fun multipleAudioTracksCanBeCreatedAndValidatedTogether() {
        val initial = MediaTimeline.empty(project)
        val withSecondAudioTrack = MediaTimelinePolicy.addTrack(initial, MediaTrackKind.AUDIO)
        val secondTrackId = withSecondAudioTrack.tracks.last().id
        val secondAudio = audio.copy(id = "audio-asset-2")
        val withClips = MediaTimelinePolicy.addClip(
            MediaTimelinePolicy.addClip(withSecondAudioTrack, "audio-main", audio),
            secondTrackId,
            secondAudio
        )
        val withVideo = MediaTimelinePolicy.addClip(withClips, "video-main", video)
        assertEquals(2, withVideo.tracks.count { it.kind == MediaTrackKind.AUDIO })
        MediaTimelinePolicy.validate(withVideo, listOf(video, audio, secondAudio))
    }

    @Test
    fun audioTrackLimitIsEnforced() {
        var timeline = MediaTimeline.empty(project)
        repeat(MediaTimelinePolicy.MAX_AUDIO_TRACKS - 1) {
            timeline = MediaTimelinePolicy.addTrack(timeline, MediaTrackKind.AUDIO)
        }
        assertEquals(MediaTimelinePolicy.MAX_AUDIO_TRACKS, timeline.tracks.count { it.kind == MediaTrackKind.AUDIO })
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.addTrack(timeline, MediaTrackKind.AUDIO)
        }
    }

    @Test
    fun everyAudioTrackMustStayWithinVideoDuration() {
        val initial = MediaTimeline.empty(project)
        val withSecondTrack = MediaTimelinePolicy.addTrack(initial, MediaTrackKind.AUDIO)
        val longAudio = audio.copy(durationMs = 12_000)
        val withFirstAudio = MediaTimelinePolicy.addClip(withSecondTrack, "audio-main", audio)
        val withLongAudio = MediaTimelinePolicy.addClip(withFirstAudio, withSecondTrack.tracks.last().id, longAudio)
        val withVideo = MediaTimelinePolicy.addClip(withLongAudio, "video-main", video)
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.validate(withVideo, listOf(video, audio, longAudio))
        }
    }

    @Test
    fun trackVolumeMustBeFiniteAndWithinSupportedRange() {
        val tooLoud = MediaTimeline.empty(project).copy(
            tracks = MediaTimeline.empty(project).tracks.map { track ->
                if (track.id == "audio-main") track.copy(volume = 2.1f) else track
            }
        )
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.validate(tooLoud, emptyList())
        }
        val invalid = MediaTimeline.empty(project).copy(
            tracks = MediaTimeline.empty(project).tracks.map { track ->
                if (track.id == "audio-main") track.copy(volume = Float.NaN) else track
            }
        )
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.validate(invalid, emptyList())
        }
    }

    @Test
    fun onlyEmptyAudioTracksCanBeRemoved() {
        val initial = MediaTimeline.empty(project)
        val withSecondTrack = MediaTimelinePolicy.addTrack(initial, MediaTrackKind.AUDIO)
        val secondTrackId = withSecondTrack.tracks.last().id
        val removed = MediaTimelinePolicy.removeTrack(withSecondTrack, secondTrackId)
        assertEquals(1, removed.tracks.count { it.kind == MediaTrackKind.AUDIO })

        val withClip = MediaTimelinePolicy.addClip(withSecondTrack, secondTrackId, audio)
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.removeTrack(withClip, secondTrackId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaTimelinePolicy.removeTrack(initial, "video-main")
        }
    }
}
