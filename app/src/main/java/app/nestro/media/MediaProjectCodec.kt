package app.nestro.media

import org.json.JSONArray
import org.json.JSONObject

object MediaProjectCodec {
    fun encodeAsset(asset: MediaAsset): JSONObject = JSONObject()
        .put("id", asset.id).put("projectId", asset.projectId).put("fileName", asset.fileName)
        .put("displayName", asset.displayName).put("mimeType", asset.mimeType).put("kind", asset.kind.name)
        .put("byteSize", asset.byteSize).put("durationMs", asset.durationMs)
        .put("width", asset.width).put("height", asset.height).put("rotationDegrees", asset.rotationDegrees)
        .put("sha256", asset.sha256).put("importedAt", asset.importedAt)

    fun decodeAsset(json: JSONObject): MediaAsset = MediaAsset(
        id = json.getString("id"), projectId = json.getString("projectId"),
        fileName = json.getString("fileName"), displayName = json.getString("displayName").take(160),
        mimeType = json.getString("mimeType"), kind = MediaAssetKind.valueOf(json.getString("kind")),
        byteSize = json.getLong("byteSize"), durationMs = json.getLong("durationMs"),
        width = json.optInt("width"), height = json.optInt("height"),
        rotationDegrees = json.optInt("rotationDegrees"), sha256 = json.getString("sha256"),
        importedAt = json.getLong("importedAt")
    )

    fun encodeTimeline(timeline: MediaTimeline): JSONObject {
        val tracks = JSONArray()
        timeline.tracks.forEach { track ->
            val clips = JSONArray()
            track.clips.forEach { clip ->
                clips.put(JSONObject().put("id", clip.id).put("assetId", clip.assetId)
                    .put("timelineStartMs", clip.timelineStartMs).put("sourceStartMs", clip.sourceStartMs)
                    .put("sourceEndMs", clip.sourceEndMs).put("muted", clip.muted))
            }
            tracks.put(JSONObject().put("id", track.id).put("name", track.name)
                .put("kind", track.kind.name).put("muted", track.muted)
                .put("volume", track.volume).put("clips", clips))
        }
        val subtitles = JSONArray()
        timeline.subtitles.forEach { cue ->
            subtitles.put(JSONObject().put("id", cue.id).put("startMs", cue.startMs)
                .put("endMs", cue.endMs).put("text", cue.text))
        }
        return JSONObject().put("projectId", timeline.projectId).put("tracks", tracks)
            .put("subtitles", subtitles).put("revision", timeline.revision).put("updatedAt", timeline.updatedAt)
    }

    fun decodeTimeline(json: JSONObject): MediaTimeline {
        val tracksJson = json.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until tracksJson.length()).map { index ->
            val row = tracksJson.getJSONObject(index)
            val clipsJson = row.optJSONArray("clips") ?: JSONArray()
            val clips = (0 until clipsJson.length()).map { clipIndex ->
                val clip = clipsJson.getJSONObject(clipIndex)
                MediaClip(
                    id = clip.getString("id"), assetId = clip.getString("assetId"),
                    timelineStartMs = clip.getLong("timelineStartMs"),
                    sourceStartMs = clip.getLong("sourceStartMs"), sourceEndMs = clip.getLong("sourceEndMs"),
                    muted = clip.optBoolean("muted")
                )
            }
            MediaTrack(
                id = row.getString("id"), name = row.getString("name"),
                kind = MediaTrackKind.valueOf(row.getString("kind")),
                muted = row.optBoolean("muted"),
                volume = row.optDouble("volume", 1.0).toFloat(),
                clips = clips
            )
        }
        val subtitleJson = json.optJSONArray("subtitles") ?: JSONArray()
        val subtitles = (0 until subtitleJson.length()).map { index ->
            val cue = subtitleJson.getJSONObject(index)
            MediaSubtitleCue(
                id = cue.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                startMs = cue.getLong("startMs"), endMs = cue.getLong("endMs"),
                text = cue.getString("text")
            )
        }
        val projectId = json.getString("projectId")
        val resolvedTracks = if (tracks.isEmpty()) MediaTimeline.empty(projectId).tracks else tracks
        return MediaTimeline(
            projectId = projectId, tracks = resolvedTracks, subtitles = subtitles,
            revision = json.optLong("revision"), updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
