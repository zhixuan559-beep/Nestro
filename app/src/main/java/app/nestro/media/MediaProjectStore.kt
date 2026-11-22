package app.nestro.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.StatFs
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object MediaProjectStore {
    const val MAX_ASSET_BYTES = 1_000_000_000L
    private const val MAX_MANIFEST_BYTES = 12_000_000L
    private const val HISTORY_LIMIT = 10
    private const val MAX_ASSETS = 500

    private fun safeProjectId(projectId: String): String {
        require(projectId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) { "项目 ID 无效" }
        return projectId
    }

    private fun projectDir(context: Context, projectId: String): File =
        File(File(context.filesDir, "media-editor"), safeProjectId(projectId)).canonicalFile

    private fun manifestFile(context: Context, projectId: String) = File(projectDir(context, projectId), "project.json")
    private fun assetsDir(context: Context, projectId: String) = File(projectDir(context, projectId), "assets")

    private fun ensureProject(context: Context, projectId: String): File {
        val root = projectDir(context, projectId)
        require(root.mkdirs() || root.isDirectory) { "无法创建媒体项目目录" }
        val mediaRoot = File(context.filesDir, "media-editor").canonicalPath + File.separator
        require(root.canonicalPath.startsWith(mediaRoot)) { "媒体项目路径越界" }
        return root
    }

    private fun readManifest(context: Context, projectId: String): JSONObject {
        val file = manifestFile(context, projectId)
        if (!file.exists()) return JSONObject()
            .put("schemaVersion", 1).put("projectId", safeProjectId(projectId))
            .put("assets", JSONArray())
            .put("timeline", MediaProjectCodec.encodeTimeline(MediaTimeline.empty(projectId)))
            .put("undo", JSONArray()).put("redo", JSONArray())
        require(file.isFile && file.length() in 1..MAX_MANIFEST_BYTES) { "媒体项目索引损坏或超出大小限制" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.optInt("schemaVersion") == 1 && json.optString("projectId") == projectId) {
            "媒体项目索引版本或项目归属不匹配"
        }
        return json
    }

    private fun writeManifest(context: Context, projectId: String, json: JSONObject) {
        ensureProject(context, projectId)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "媒体项目索引超过大小限制" }
        writeAtomically(manifestFile(context, projectId), bytes)
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.let { require(it.mkdirs() || it.isDirectory) { "无法创建索引目录" } }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun listAssets(context: Context, projectId: String): List<MediaAsset> {
        val rows = readManifest(context, projectId).optJSONArray("assets") ?: JSONArray()
        val assets = (0 until rows.length()).map { index ->
            MediaProjectCodec.decodeAsset(rows.getJSONObject(index)).also { asset ->
                require(asset.projectId == projectId) { "素材索引包含其他项目的素材" }
                val file = assetFile(context, asset)
                require(file.isFile && file.length() == asset.byteSize) { "素材文件缺失或大小已变化：" + asset.displayName }
            }
        }
        require(assets.map { it.id }.distinct().size == assets.size) { "素材索引包含重复 ID" }
        assets.sortedByDescending { it.importedAt }
    }

    @Synchronized
    fun importAsset(context: Context, projectId: String, uri: Uri, displayNameOverride: String? = null): MediaAsset {
        safeProjectId(projectId)
        ensureProject(context, projectId)
        require(displayNameOverride == null || (displayNameOverride.isNotBlank() && displayNameOverride.length <= 160)) { "素材显示名称无效" }
        val resolver = context.contentResolver
        var declaredSize = -1L
        var displayName = displayNameOverride?.trim() ?: "媒体素材"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameOverride.isNullOrBlank() && nameIndex >= 0) displayName = cursor.getString(nameIndex)?.take(160)?.ifBlank { null } ?: displayName
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
        require(declaredSize <= MAX_ASSET_BYTES) { "媒体文件超过 1 GB 限制" }
        require((readManifest(context, projectId).optJSONArray("assets")?.length() ?: 0) < MAX_ASSETS) { "项目素材数量达到 500 项上限" }
        val suppliedMime = resolver.getType(uri)?.lowercase().orEmpty()
        val kind = resolveKind(suppliedMime, displayName)
        val extension = resolveExtension(suppliedMime, displayName, kind)
        val mime = suppliedMime.ifBlank { mimeFor(extension, kind) }
        val id = UUID.randomUUID().toString()
        val directory = assetsDir(context, projectId)
        require(directory.mkdirs() || directory.isDirectory) { "无法创建素材目录" }
        val target = File(directory, "$id.$extension")
        val staging = File(directory, ".$id.importing")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val available = StatFs(directory.absolutePath).availableBytes
            if (declaredSize > 0) require(available > declaredSize + 32L * 1024 * 1024) { "设备剩余空间不足" }
            else require(available > 32L * 1024 * 1024) { "设备剩余空间不足" }
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ASSET_BYTES) { "媒体文件超过 1 GB 限制" }
                        require(copied + 32L * 1024 * 1024 <= available) { "设备剩余空间不足，已停止导入" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("无法读取所选媒体文件")
            require(copied > 0) { "媒体文件为空" }
            require(declaredSize <= 0 || copied == declaredSize) { "媒体文件读取不完整" }
            require(staging.renameTo(target)) { "无法完成素材导入" }
            val metadata = readMetadata(target, kind)
            val asset = MediaAsset(
                id = id, projectId = projectId, fileName = target.name, displayName = displayName,
                mimeType = mime, kind = kind, byteSize = copied, durationMs = metadata.durationMs,
                width = metadata.width, height = metadata.height, rotationDegrees = metadata.rotation,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                importedAt = System.currentTimeMillis()
            )
            val manifest = readManifest(context, projectId)
            val assets = manifest.optJSONArray("assets") ?: JSONArray()
            assets.put(MediaProjectCodec.encodeAsset(asset))
            manifest.put("assets", assets)
            writeManifest(context, projectId, manifest)
            return asset
        } catch (error: Throwable) {
            staging.delete()
            target.delete()
            throw error
        }
    }

    private data class ExtractedMetadata(val durationMs: Long, val width: Int, val height: Int, val rotation: Int)

    private fun readMetadata(file: File, kind: MediaAssetKind): ExtractedMetadata {
        if (kind == MediaAssetKind.IMAGE) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            var width = options.outWidth
            var height = options.outHeight
            if ((width <= 0 || height <= 0) && Build.VERSION.SDK_INT >= 28) {
                val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    width = info.size.width
                    height = info.size.height
                    val largestSide = maxOf(width, height)
                    if (largestSide > 4_096) decoder.setTargetSampleSize((largestSide + 4_095) / 4_096)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                decoded.recycle()
            }
            require(width > 0 && height > 0) { "图片文件无法解析" }
            return ExtractedMetadata(5_000L, width, height, 0)
        }
        val retriever = MediaMetadataRetriever()
        var duration = 0L
        var width = 0
        var height = 0
        var rotation = 0
        try {
            runCatching {
                retriever.setDataSource(file.absolutePath)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            }
        } finally {
            runCatching { retriever.release() }
        }
        if (duration <= 0) duration = extractDurationWithMediaExtractor(file)
        require(duration > 0) { "无法读取媒体时长或文件格式不受支持" }
        return ExtractedMetadata(duration, width, height, rotation)
    }

    private fun extractDurationWithMediaExtractor(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).asSequence().mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1_000L
                } else null
            }.maxOrNull() ?: 0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun resolveKind(mime: String, name: String): MediaAssetKind {
        val lower = name.lowercase()
        return when {
            mime.startsWith("video/") || listOf(".mp4", ".mov", ".mkv", ".webm").any(lower::endsWith) -> MediaAssetKind.VIDEO
            mime.startsWith("audio/") || listOf(".mp3", ".m4a", ".wav", ".aac", ".ogg", ".flac").any(lower::endsWith) -> MediaAssetKind.AUDIO
            mime.startsWith("image/") || listOf(".jpg", ".jpeg", ".png", ".webp", ".heic").any(lower::endsWith) -> MediaAssetKind.IMAGE
            else -> error("仅支持视频、音频和图片素材")
        }
    }

    private fun resolveExtension(mime: String, name: String, kind: MediaAssetKind): String {
        val known = when (mime) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            "audio/ogg" -> "ogg"
            "audio/flac" -> "flac"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            else -> null
        }
        if (known != null) return known
        val extension = name.substringAfterLast('.', "").lowercase()
        val allowed = when (kind) {
            MediaAssetKind.VIDEO -> setOf("mp4", "mov", "mkv", "webm")
            MediaAssetKind.AUDIO -> setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")
            MediaAssetKind.IMAGE -> setOf("jpg", "jpeg", "png", "webp", "heic")
        }
        return extension.takeIf { it in allowed } ?: when (kind) {
            MediaAssetKind.VIDEO -> "mp4"
            MediaAssetKind.AUDIO -> "m4a"
            MediaAssetKind.IMAGE -> "jpg"
        }
    }

    private fun mimeFor(extension: String, kind: MediaAssetKind): String = when (extension) {
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> if (kind == MediaAssetKind.VIDEO) "video/webm" else "audio/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    @Synchronized
    fun importGeneratedFile(context: Context, projectId: String, source: File, kind: MediaAssetKind, stableAssetId: String? = null): MediaAsset {
        safeProjectId(projectId)
        require(source.isFile && source.length() in 1..MAX_ASSET_BYTES) { "处理后的媒体文件不存在或超过 1 GB" }
        require(kind == MediaAssetKind.AUDIO || kind == MediaAssetKind.VIDEO || kind == MediaAssetKind.IMAGE)
        ensureProject(context, projectId)
        if (stableAssetId != null) {
            require(stableAssetId.matches(Regex("^[A-Fa-f0-9-]{36}$"))) { "处理结果素材 ID 无效" }
            val existingRows = readManifest(context, projectId).optJSONArray("assets") ?: JSONArray()
            for (index in 0 until existingRows.length()) {
                val existing = MediaProjectCodec.decodeAsset(existingRows.getJSONObject(index))
                if (existing.id == stableAssetId) {
                    require(existing.projectId == projectId && existing.kind == kind) { "幂等素材 ID 已被其他素材占用" }
                    val existingFile = assetFile(context, existing)
                    require(existingFile.isFile && existingFile.length() == existing.byteSize) { "已存在的处理结果素材损坏" }
                    return existing
                }
            }
        }
        require((readManifest(context, projectId).optJSONArray("assets")?.length() ?: 0) < MAX_ASSETS) { "项目素材数量达到 500 项上限" }
        val extension = source.extension.lowercase().takeIf { it.matches(Regex("^[a-z0-9]{2,5}$")) }
            ?: error("处理结果缺少有效文件扩展名")
        val allowedExtensions = when (kind) {
            MediaAssetKind.VIDEO -> setOf("mp4", "mov", "mkv", "webm")
            MediaAssetKind.AUDIO -> setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")
            MediaAssetKind.IMAGE -> setOf("jpg", "jpeg", "png", "webp", "heic")
        }
        require(extension in allowedExtensions) { "处理结果文件类型与素材类别不匹配" }
        val mime = mimeFor(extension, kind)
        val id = stableAssetId ?: UUID.randomUUID().toString()
        val directory = assetsDir(context, projectId)
        require(directory.mkdirs() || directory.isDirectory)
        require(StatFs(directory.absolutePath).availableBytes > source.length() + 32L * 1024 * 1024) {
            "设备剩余空间不足，无法保存处理结果"
        }
        val target = File(directory, id + "." + extension)
        val staging = File(directory, "." + id + ".importing")
        if (target.exists()) require(target.delete()) { "无法清理上次中断的处理结果临时文件" }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ASSET_BYTES) { "处理结果超过 1 GB" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(staging.renameTo(target)) { "无法保存处理结果" }
            val metadata = readMetadata(target, kind)
            val asset = MediaAsset(
                id = id, projectId = projectId, fileName = target.name,
                displayName = source.name.take(160), mimeType = mime, kind = kind,
                byteSize = target.length(), durationMs = metadata.durationMs,
                width = metadata.width, height = metadata.height, rotationDegrees = metadata.rotation,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                importedAt = System.currentTimeMillis()
            )
            val manifest = readManifest(context, projectId)
            val assets = manifest.optJSONArray("assets") ?: JSONArray()
            assets.put(MediaProjectCodec.encodeAsset(asset))
            manifest.put("assets", assets)
            writeManifest(context, projectId, manifest)
            return asset
        } catch (error: Throwable) {
            staging.delete()
            target.delete()
            throw error
        }
    }

    @Synchronized
    fun hasProjectMedia(context: Context, projectId: String): Boolean {
        val root = projectDir(context, projectId)
        val tasks = File(File(context.filesDir, "media-editor/tasks"), projectId)
        if (!root.exists() && !tasks.exists()) return false
        val manifest = if (root.exists()) readManifest(context, projectId) else JSONObject()
        val assets = manifest.optJSONArray("assets") ?: JSONArray()
        val timeline = manifest.optJSONObject("timeline")?.let(MediaProjectCodec::decodeTimeline)
        val hasClips = timeline?.tracks?.any { it.clips.isNotEmpty() } == true
        val hasSubtitles = timeline?.subtitles?.isNotEmpty() == true
        val orphanedAssetFiles = File(root, "assets").listFiles()?.any { it.isFile } == true
        val exports = File(root, "exports").listFiles()?.any { it.isFile && it.length() > 0 } == true
        val taskFiles = tasks.listFiles()?.any { it.isFile && it.extension == "json" } == true
        return assets.length() > 0 || orphanedAssetFiles || hasClips || hasSubtitles || exports || taskFiles
    }

    fun assetFile(context: Context, asset: MediaAsset): File {
        safeProjectId(asset.projectId)
        require(asset.id.matches(Regex("^[A-Fa-f0-9-]{36}$"))) { "素材 ID 无效" }
        require(asset.fileName.matches(Regex("^[A-Fa-f0-9-]{36}\\.[A-Za-z0-9]{2,5}$")) &&
            asset.fileName.startsWith(asset.id + ".")) { "素材文件名无效" }
        val directory = assetsDir(context, asset.projectId).canonicalFile
        val file = File(directory, asset.fileName).canonicalFile
        require(file.parentFile == directory) { "素材路径越界" }
        return file
    }

    @Synchronized
    fun readTimeline(context: Context, projectId: String): MediaTimeline {
        val timeline = MediaProjectCodec.decodeTimeline(readManifest(context, projectId).getJSONObject("timeline"))
        require(timeline.projectId == projectId) { "时间线项目归属不匹配" }
        MediaTimelinePolicy.validate(timeline, listAssets(context, projectId))
        return timeline
    }

    @Synchronized
    fun saveTimeline(context: Context, timeline: MediaTimeline, expectedRevision: Long): MediaTimeline {
        val manifest = readManifest(context, timeline.projectId)
        val current = MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline"))
        require(current.revision == expectedRevision) { "时间线已被其他操作更新，请刷新后重试" }
        MediaTimelinePolicy.validate(timeline, listAssets(context, timeline.projectId))
        val next = timeline.copy(revision = current.revision + 1, updatedAt = System.currentTimeMillis())
        val undo = manifest.optJSONArray("undo") ?: JSONArray()
        undo.put(MediaProjectCodec.encodeTimeline(current))
        val boundedUndo = JSONArray()
        for (index in (undo.length() - HISTORY_LIMIT).coerceAtLeast(0) until undo.length()) {
            boundedUndo.put(undo.getJSONObject(index))
        }
        manifest.put("timeline", MediaProjectCodec.encodeTimeline(next))
            .put("undo", boundedUndo).put("redo", JSONArray())
        writeManifest(context, timeline.projectId, manifest)
        return next
    }

    @Synchronized
    fun undo(context: Context, projectId: String): MediaTimeline {
        val manifest = readManifest(context, projectId)
        val undo = manifest.optJSONArray("undo") ?: JSONArray()
        if (undo.length() == 0) return MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline"))
        val current = MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline"))
        val previous = MediaProjectCodec.decodeTimeline(undo.getJSONObject(undo.length() - 1))
        val nextUndo = JSONArray()
        for (index in 0 until undo.length() - 1) nextUndo.put(undo.getJSONObject(index))
        val redo = manifest.optJSONArray("redo") ?: JSONArray()
        redo.put(MediaProjectCodec.encodeTimeline(current))
        val next = previous.copy(revision = current.revision + 1, updatedAt = System.currentTimeMillis())
        MediaTimelinePolicy.validate(next, listAssets(context, projectId))
        manifest.put("undo", nextUndo).put("redo", redo).put("timeline", MediaProjectCodec.encodeTimeline(next))
        writeManifest(context, projectId, manifest)
        return next
    }

    @Synchronized
    fun redo(context: Context, projectId: String): MediaTimeline {
        val manifest = readManifest(context, projectId)
        val redo = manifest.optJSONArray("redo") ?: JSONArray()
        if (redo.length() == 0) return MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline"))
        val current = MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline"))
        val target = MediaProjectCodec.decodeTimeline(redo.getJSONObject(redo.length() - 1))
        val nextRedo = JSONArray()
        for (index in 0 until redo.length() - 1) nextRedo.put(redo.getJSONObject(index))
        val undo = manifest.optJSONArray("undo") ?: JSONArray()
        undo.put(MediaProjectCodec.encodeTimeline(current))
        val next = target.copy(revision = current.revision + 1, updatedAt = System.currentTimeMillis())
        MediaTimelinePolicy.validate(next, listAssets(context, projectId))
        manifest.put("redo", nextRedo).put("undo", undo).put("timeline", MediaProjectCodec.encodeTimeline(next))
        writeManifest(context, projectId, manifest)
        return next
    }

    fun exportFile(context: Context, projectId: String, taskId: String): File {
        safeProjectId(projectId)
        require(taskId.matches(Regex("^[A-Fa-f0-9-]{36}$"))) { "导出任务 ID 无效" }
        val directory = File(projectDir(context, projectId), "exports")
        require(directory.mkdirs() || directory.isDirectory) { "无法创建导出目录" }
        val file = File(directory, "export-" + taskId + ".mp4").canonicalFile
        require(file.parentFile == directory.canonicalFile) { "导出路径越界" }
        return file
    }

    /**
     * Exports a complete editable media project (manifest + original binary assets).
     * Rendered exports and transient task files are intentionally excluded.
     */
    @Synchronized
    fun exportProjectArchive(context: Context, projectId: String, destination: File): File {
        safeProjectId(projectId)
        val assets = listAssets(context, projectId)
        val manifest = readManifest(context, projectId)
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        val target = destination.canonicalFile
        require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
            "无法创建媒体项目备份目录"
        }
        val staging = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        var totalBytes = manifestBytes.size.toLong()
        try {
            ZipOutputStream(FileOutputStream(staging)).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
                assets.forEach { asset ->
                    val source = assetFile(context, asset)
                    require(source.isFile && source.length() == asset.byteSize) { "素材缺失或大小异常：${asset.displayName}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    zip.putNextEntry(ZipEntry("assets/${asset.fileName}"))
                    FileInputStream(source).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            totalBytes += count
                            require(totalBytes <= 2_000_000_000L) { "媒体项目备份超过 2 GB 限制" }
                            digest.update(buffer, 0, count)
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    require(copied == asset.byteSize &&
                        digest.digest().joinToString("") { "%02x".format(it) }.equals(asset.sha256, true)) {
                        "素材完整性校验失败：${asset.displayName}"
                    }
                }
            }
            require(staging.renameTo(target) || run {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }) { "无法完成媒体项目备份文件写入" }
            return target
        } catch (error: Throwable) {
            staging.delete()
            throw error
        }
    }

    /**
     * Imports a complete media project into a new project ID. Existing project
     * directories are never overwritten; every asset is size- and hash-verified.
     */
    @Synchronized
    fun importProjectArchive(context: Context, archive: File, targetProjectId: String): MediaTimeline {
        safeProjectId(targetProjectId)
        require(archive.isFile && archive.length() in 1..2_000_000_000L) { "媒体项目备份不存在或超出 2 GB 限制" }
        val mediaRoot = File(context.filesDir, "media-editor").canonicalFile
        require(mediaRoot.mkdirs() || mediaRoot.isDirectory)
        val target = projectDir(context, targetProjectId)
        require(!target.exists()) { "目标媒体项目已存在，拒绝覆盖" }
        val staging = File(mediaRoot, ".import-${UUID.randomUUID()}").canonicalFile
        require(staging.parentFile == mediaRoot && staging.mkdirs()) { "无法创建媒体项目导入暂存目录" }
        try {
            var manifestRaw: String? = null
            val extracted = linkedMapOf<String, File>()
            var totalBytes = 0L
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "媒体项目备份包含不支持的目录项" }
                    when {
                        entry.name == "project.json" -> {
                            require(manifestRaw == null) { "媒体项目备份包含重复索引" }
                            val bytes = zip.readBytes()
                            require(bytes.size in 1..MAX_MANIFEST_BYTES.toInt()) { "媒体项目索引超出大小限制" }
                            manifestRaw = bytes.toString(Charsets.UTF_8)
                        }
                        entry.name.startsWith("assets/") -> {
                            val fileName = entry.name.removePrefix("assets/")
                            require(fileName.matches(Regex("^[A-Fa-f0-9-]{36}\\.[A-Za-z0-9]{2,5}$")) &&
                                extracted[fileName] == null) { "媒体项目备份包含无效或重复素材路径" }
                            val file = File(File(staging, "assets"), fileName).canonicalFile
                            val assetDirectory = File(staging, "assets").canonicalFile
                            require(file.parentFile == assetDirectory) { "媒体项目素材路径越界" }
                            require(assetDirectory.mkdirs() || assetDirectory.isDirectory)
                            val available = StatFs(mediaRoot.absolutePath).availableBytes
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    totalBytes += count
                                    require(totalBytes <= 2_000_000_000L &&
                                        totalBytes + 64L * 1024 * 1024 < available) { "设备空间不足或媒体项目备份超出容量限制" }
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                            extracted[fileName] = file
                        }
                        else -> error("媒体项目备份包含未知文件：${entry.name}")
                    }
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(requireNotNull(manifestRaw) { "媒体项目备份缺少索引" })
            val sourceProjectId = manifest.optString("projectId")
            require(manifest.optInt("schemaVersion") == 1 &&
                sourceProjectId.matches(Regex("^[A-Za-z0-9_-]{1,80}$"))) { "媒体项目索引版本或来源项目无效" }
            val rows = manifest.optJSONArray("assets") ?: JSONArray()
            require(rows.length() <= MAX_ASSETS && rows.length() == extracted.size) { "媒体项目素材数量与索引不一致" }
            val importedAssets = mutableListOf<MediaAsset>()
            val rewrittenAssets = JSONArray()
            for (index in 0 until rows.length()) {
                val sourceAsset = MediaProjectCodec.decodeAsset(rows.getJSONObject(index))
                require(sourceAsset.projectId == sourceProjectId &&
                    extracted.containsKey(sourceAsset.fileName) &&
                    sourceAsset.byteSize in 1..MAX_ASSET_BYTES) { "媒体项目素材元数据无效" }
                val file = extracted.getValue(sourceAsset.fileName)
                require(file.isFile && file.length() == sourceAsset.byteSize) { "媒体素材大小校验失败：${sourceAsset.displayName}" }
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) }.equals(sourceAsset.sha256, true)) {
                    "媒体素材 SHA-256 校验失败：${sourceAsset.displayName}"
                }
                val imported = sourceAsset.copy(projectId = targetProjectId)
                importedAssets += imported
                rewrittenAssets.put(MediaProjectCodec.encodeAsset(imported))
            }
            require(importedAssets.map { it.id }.distinct().size == importedAssets.size) { "媒体项目包含重复素材 ID" }
            val timeline = MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline")).copy(projectId = targetProjectId)
            MediaTimelinePolicy.validate(timeline, importedAssets)
            manifest.put("projectId", targetProjectId)
                .put("assets", rewrittenAssets)
                .put("timeline", MediaProjectCodec.encodeTimeline(timeline))
            listOf("undo", "redo").forEach { key ->
                val history = manifest.optJSONArray(key) ?: JSONArray()
                val rewritten = JSONArray()
                for (index in 0 until history.length()) {
                    rewritten.put(MediaProjectCodec.encodeTimeline(
                        MediaProjectCodec.decodeTimeline(history.getJSONObject(index)).copy(projectId = targetProjectId)
                    ))
                }
                manifest.put(key, rewritten)
            }
            writeAtomically(File(staging, "project.json"), manifest.toString().toByteArray(Charsets.UTF_8))
            require(staging.renameTo(target)) { "无法提交媒体项目导入结果" }
            return timeline
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun duplicateProject(context: Context, sourceProjectId: String, targetProjectId: String) {
        safeProjectId(sourceProjectId)
        safeProjectId(targetProjectId)
        require(sourceProjectId != targetProjectId)
        val source = projectDir(context, sourceProjectId)
        if (!source.exists()) return
        val target = projectDir(context, targetProjectId)
        require(!target.exists()) { "目标媒体项目已存在" }
        require(target.mkdirs()) { "无法创建目标媒体项目" }
        try {
            source.walkTopDown().onEnter { directory -> directory == source || directory.name != "exports" }.forEach { item ->
                require(!java.nio.file.Files.isSymbolicLink(item.toPath())) { "媒体项目含符号链接，拒绝复制" }
                val destination = File(target, item.relativeTo(source).path).canonicalFile
                require(destination.path == target.canonicalPath ||
                    destination.path.startsWith(target.canonicalPath + File.separator)) { "媒体项目复制路径越界" }
                if (item.isDirectory) {
                    require(destination.mkdirs() || destination.isDirectory)
                } else {
                    destination.parentFile?.mkdirs()
                    FileInputStream(item).use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
                }
            }
            val manifest = readManifest(context, sourceProjectId)
            val assets = manifest.optJSONArray("assets") ?: JSONArray()
            val remappedAssets = JSONArray()
            for (index in 0 until assets.length()) {
                remappedAssets.put(MediaProjectCodec.encodeAsset(
                    MediaProjectCodec.decodeAsset(assets.getJSONObject(index)).copy(projectId = targetProjectId)
                ))
            }
            manifest.put("projectId", targetProjectId).put("assets", remappedAssets)
            val timeline = MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline"))
            manifest.put("timeline", MediaProjectCodec.encodeTimeline(timeline.copy(projectId = targetProjectId)))
            listOf("undo", "redo").forEach { key ->
                val history = manifest.optJSONArray(key) ?: JSONArray()
                val rewritten = JSONArray()
                for (index in 0 until history.length()) {
                    rewritten.put(MediaProjectCodec.encodeTimeline(
                        MediaProjectCodec.decodeTimeline(history.getJSONObject(index)).copy(projectId = targetProjectId)
                    ))
                }
                manifest.put(key, rewritten)
            }
            writeManifest(context, targetProjectId, manifest)
        } catch (error: Throwable) {
            target.deleteRecursively()
            throw error
        }
    }

    fun hasProjectData(context: Context, projectId: String): Boolean = projectDir(context, projectId).exists()

    @Synchronized
    fun deleteProject(context: Context, projectId: String) {
        safeProjectId(projectId)
        val root = projectDir(context, projectId)
        if (root.exists()) {
            val mediaRoot = File(context.filesDir, "media-editor").canonicalFile
            require(root.canonicalPath.startsWith(mediaRoot.canonicalPath + File.separator)) { "拒绝删除媒体根目录之外的路径" }
            require(root.deleteRecursively()) { "媒体项目数据删除失败" }
        }
        val taskRoot = File(File(context.filesDir, "media-editor/tasks"), projectId).canonicalFile
        val taskBase = File(context.filesDir, "media-editor/tasks").canonicalFile
        require(taskRoot.path.startsWith(taskBase.path + File.separator)) { "任务删除路径越界" }
        if (taskRoot.exists()) require(taskRoot.deleteRecursively()) { "媒体任务数据删除失败" }
    }

    @Synchronized
    fun deleteAsset(context: Context, projectId: String, assetId: String) {
        val manifest = readManifest(context, projectId)
        val timeline = MediaProjectCodec.decodeTimeline(manifest.getJSONObject("timeline"))
        require(timeline.tracks.none { track -> track.clips.any { it.assetId == assetId } }) {
            "素材仍被当前时间线引用，请先删除相关剪辑"
        }
        // Deleting an unused asset invalidates snapshots that could restore references to it.
        manifest.put("undo", JSONArray()).put("redo", JSONArray())
        val assets = manifest.optJSONArray("assets") ?: JSONArray()
        val assetIndex = (0 until assets.length()).firstOrNull {
            assets.getJSONObject(it).optString("id") == assetId
        } ?: return
        val originalManifest = JSONObject(manifest.toString())
        val asset = MediaProjectCodec.decodeAsset(assets.getJSONObject(assetIndex))
        val file = assetFile(context, asset)
        val staged = File(file.parentFile, "." + file.name + ".deleting")
        if (file.exists()) require(file.renameTo(staged)) { "无法暂存待删除素材" }
        try {
            val next = JSONArray()
            for (index in 0 until assets.length()) if (index != assetIndex) next.put(assets.getJSONObject(index))
            manifest.put("assets", next)
            writeManifest(context, projectId, manifest)
            if (staged.exists() && !staged.delete()) {
                val manifestRestored = runCatching { writeManifest(context, projectId, originalManifest) }.isSuccess
                val fileRestored = staged.renameTo(file)
                require(manifestRestored && fileRestored) { "素材删除失败，且自动回滚未能完整完成；请保留项目数据并检查存储空间" }
                error("素材文件无法清理，已恢复原始素材和索引")
            }
        } catch (error: Throwable) {
            if (staged.exists()) staged.renameTo(file)
            throw error
        }
    }
}
