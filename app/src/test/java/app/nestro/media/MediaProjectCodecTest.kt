package app.nestro.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProjectCodecTest {
    @Test fun timelineRoundTripPreservesTrackClipsSubtitlesAndRevision() {
        val base = MediaTimeline.empty("project-a")
        val video = MediaAsset(
            id = "asset-a", projectId = "project-a", fileName = "asset-a.mp4", displayName = "clip.mp4",
            mimeType = "video/mp4", kind = MediaAssetKind.VIDEO, byteSize = 100,
            durationMs = 12_000, sha256 = "abc", importedAt = 10
        )
        val withAudioTrack = MediaTimelinePolicy.addTrack(base, MediaTrackKind.AUDIO)
        val withVideoClip = MediaTimelinePolicy.addClip(withAudioTrack, "video-main", video)
        val withClip = withVideoClip.copy(
            revision = 7,
            tracks = withVideoClip.tracks.map { track ->
                when (track.id) {
                    "audio-main" -> track.copy(volume = 0.75f)
                    else -> track.copy(volume = 1.25f)
                }
            },
            subtitles = listOf(MediaSubtitleCue(startMs = 1_000, endMs = 2_500, text = "字幕"))
        )
        val decoded = MediaProjectCodec.decodeTimeline(MediaProjectCodec.encodeTimeline(withClip))
        assertEquals(withClip.projectId, decoded.projectId)
        assertEquals(7L, decoded.revision)
        assertEquals(withClip.tracks, decoded.tracks)
        assertEquals(withClip.subtitles, decoded.subtitles)
    }

    @Test fun legacyTimelineWithoutVolumeDefaultsToUnityGain() {
        val encoded = MediaProjectCodec.encodeTimeline(MediaTimeline.empty("project-a"))
        val tracks = encoded.getJSONArray("tracks")
        for (index in 0 until tracks.length()) {
            tracks.getJSONObject(index).remove("volume")
        }
        val decoded = MediaProjectCodec.decodeTimeline(encoded)
        assertTrue(decoded.tracks.all { it.volume == 1f })
    }

    @Test fun assetCodecPreservesProjectOwnershipAndDigest() {
        val asset = MediaAsset(
            id = "asset-a", projectId = "project-a", fileName = "asset-a.mp4", displayName = "clip.mp4",
            mimeType = "video/mp4", kind = MediaAssetKind.VIDEO, byteSize = 100,
            durationMs = 12_000, sha256 = "digest", importedAt = 10
        )
        val decoded = MediaProjectCodec.decodeAsset(MediaProjectCodec.encodeAsset(asset))
        assertEquals(asset, decoded)
        assertTrue(decoded.sha256 == "digest")
    }
}
