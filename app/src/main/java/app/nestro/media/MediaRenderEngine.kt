package app.nestro.media

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import java.io.File

@UnstableApi
object MediaRenderEngine {
    fun buildComposition(context: Context, projectId: String): Composition {
        val assets = MediaProjectStore.listAssets(context, projectId).associateBy { it.id }
        val timeline = MediaProjectStore.readTimeline(context, projectId)
        MediaTimelinePolicy.validate(timeline, assets.values.toList())
        val videoTrack = timeline.tracks.single { it.kind == MediaTrackKind.VIDEO }
        val videoClips = videoTrack.clips.sortedBy { it.timelineStartMs }
        require(videoClips.isNotEmpty()) { "时间线没有视频或图片片段" }

        val videoBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO))
        var cursor = 0L
        videoClips.forEach { clip ->
            if (clip.timelineStartMs > cursor) videoBuilder.addGap((clip.timelineStartMs - cursor) * 1_000L)
            val asset = assets[clip.assetId] ?: error("视频轨道引用了不存在的素材")
            videoBuilder.addItem(editedItem(context, asset, clip, removeAudio = videoTrack.muted || clip.muted, audioVolume = videoTrack.volume))
            cursor = clip.timelineEndMs
        }
        val sequences = mutableListOf(videoBuilder.build())

        val videoDurationMs = videoClips.maxOf { it.timelineEndMs }
        timeline.tracks.filter { it.kind == MediaTrackKind.AUDIO }.forEach { audioTrack ->
            val boundedAudioClips = audioTrack.clips
                .filter { it.timelineStartMs < videoDurationMs }
                .map { clip ->
                    val boundedEnd = minOf(clip.timelineEndMs, videoDurationMs)
                    clip.copy(sourceEndMs = clip.sourceStartMs + (boundedEnd - clip.timelineStartMs))
                }
            if (!audioTrack.muted && boundedAudioClips.any { !it.muted }) {
                val audioBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var audioCursor = 0L
                boundedAudioClips.forEach { clip ->
                    if (clip.timelineStartMs > audioCursor) audioBuilder.addGap((clip.timelineStartMs - audioCursor) * 1_000L)
                    if (clip.muted) {
                        audioBuilder.addGap(clip.durationMs * 1_000L)
                    } else {
                        val asset = assets[clip.assetId] ?: error("音频轨道引用了不存在的素材")
                        audioBuilder.addItem(editedItem(context, asset, clip, audioVolume = audioTrack.volume))
                    }
                    audioCursor = clip.timelineEndMs
                }
                sequences += audioBuilder.build()
            }
        }

        val builder = Composition.Builder(sequences)
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        return builder.build()
    }

    private fun normalizedAudioEffects(volume: Float): Effects {
        val channelMixer = ChannelMixingAudioProcessor()
        for (inputChannels in 1..6) {
            channelMixer.putChannelMixingMatrix(
                ChannelMixingMatrix.createForConstantPower(inputChannels, 2).scaleBy(volume)
            )
        }
        return Effects(listOf(ToInt16PcmAudioProcessor(), channelMixer), emptyList())
    }

    private fun editedItem(
        context: Context,
        asset: MediaAsset,
        clip: MediaClip,
        removeAudio: Boolean = false,
        audioVolume: Float = 1f
    ): EditedMediaItem {
        val file: File = MediaProjectStore.assetFile(context, asset)
        require(file.isFile && file.length() == asset.byteSize) { "素材文件缺失或大小已变化：" + asset.displayName }
        val mediaBuilder = MediaItem.Builder().setUri(Uri.fromFile(file))
        if (asset.kind == MediaAssetKind.IMAGE) {
            mediaBuilder.setImageDurationMs(clip.durationMs)
        } else {
            mediaBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.sourceStartMs)
                    .setEndPositionMs(clip.sourceEndMs)
                    .build()
            )
        }
        val editedBuilder = EditedMediaItem.Builder(mediaBuilder.build())
        if (asset.kind == MediaAssetKind.IMAGE) {
            editedBuilder.setFrameRate(30)
        } else {
            // Media3 expects original source duration; clipping is configured on MediaItem.
            editedBuilder.setDurationUs(asset.durationMs * 1_000L)
        }
        if (removeAudio || asset.kind == MediaAssetKind.IMAGE) {
            editedBuilder.setRemoveAudio(true)
        } else {
            editedBuilder.setEffects(normalizedAudioEffects(audioVolume))
        }
        return editedBuilder.build()
    }
}
