package app.nestro.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import app.nestro.data.ChatMessage
import app.nestro.data.ChatMessageMediaPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Resolves existing project asset_id markers into ephemeral provider-native media parts.
 * The project media store remains the only owner of source files; Room stores only the marker.
 */
internal object ChatMediaAttachmentResolver {
    private const val MAX_IMAGE_EDGE = 1_600
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_INLINE_MEDIA_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_MEDIA_BYTES = 12 * 1024 * 1024
    private const val MAX_IMAGES_PER_REQUEST = 10
    private const val MAX_AUDIO_FILES_PER_REQUEST = 1
    private const val MAX_VIDEO_FILES_PER_REQUEST = 10
    private val assetIdPattern = Regex("""asset_id=([A-Fa-f0-9-]{36})""")
    private val passThroughImageMimeTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    private val supportedAudioMimeTypes = setOf(
        "audio/aac", "audio/flac", "audio/mp3", "audio/mpeg", "audio/m4a",
        "audio/mp4", "audio/ogg", "audio/opus", "audio/pcm", "audio/wav",
        "audio/webm", "audio/mpga", "audio/x-wav"
    )
    private val supportedVideoMimeTypes = setOf(
        "video/x-flv", "video/quicktime", "video/mpeg", "video/mpegps",
        "video/mpg", "video/mp4", "video/webm", "video/wmv", "video/3gpp"
    )

    internal fun referencedAssetIds(content: String): List<String> =
        assetIdPattern.findAll(content).map { it.groupValues[1] }.distinct().toList()

    suspend fun resolve(
        context: Context,
        projectId: String,
        history: List<ChatMessage>
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        val recentHistory = history.takeLast(40)
        if (projectId.isBlank() || recentHistory.none { it.role == "user" && "asset_id=" in it.content }) {
            return@withContext history
        }
        val requestedIds = recentHistory.asReversed()
            .filter { it.role == "user" }
            .flatMap { referencedAssetIds(it.content) }
            .distinct()
        if (requestedIds.isEmpty()) return@withContext history

        val assets = runCatching {
            MediaProjectStore.listAssets(context, projectId).associateBy { it.id }
        }.getOrElse { return@withContext history }

        var remainingBytes = MAX_TOTAL_MEDIA_BYTES
        var imageCount = 0
        var audioCount = 0
        var videoCount = 0
        val prepared = linkedMapOf<String, ChatMessageMediaPart>()
        for (assetId in requestedIds) {
            if (remainingBytes <= 0) break
            val asset = assets[assetId] ?: continue
            val part = when (asset.kind) {
                MediaAssetKind.IMAGE -> {
                    if (imageCount >= MAX_IMAGES_PER_REQUEST) null
                    else runCatching { prepareImage(context, asset) }.getOrNull()
                }
                MediaAssetKind.AUDIO -> {
                    if (audioCount >= MAX_AUDIO_FILES_PER_REQUEST) null
                    else runCatching { prepareInlineMedia(context, asset, 2_048) }.getOrNull()
                }
                MediaAssetKind.VIDEO -> {
                    if (videoCount >= MAX_VIDEO_FILES_PER_REQUEST) null
                    else runCatching { prepareInlineMedia(context, asset, 4_096) }.getOrNull()
                }
            } ?: continue
            val byteCount = (part.dataBase64.length * 3L / 4L).toInt()
            if (byteCount !in 1..remainingBytes) continue
            prepared[assetId] = part
            remainingBytes -= byteCount
            when (asset.kind) {
                MediaAssetKind.IMAGE -> imageCount++
                MediaAssetKind.AUDIO -> audioCount++
                MediaAssetKind.VIDEO -> videoCount++
            }
        }

        history.map { message ->
            if (message.role != "user") message
            else {
                val parts = referencedAssetIds(message.content).mapNotNull(prepared::get)
                if (parts.isEmpty()) message else message.copy(mediaParts = parts)
            }
        }
    }

    private fun prepareInlineMedia(
        context: Context,
        asset: MediaAsset,
        estimatedTokens: Int
    ): ChatMessageMediaPart? {
        val file = MediaProjectStore.assetFile(context, asset)
        if (!file.isFile || file.length() <= 0L || file.length() != asset.byteSize ||
            file.length() > MAX_INLINE_MEDIA_BYTES
        ) return null
        val mime = asset.mimeType.lowercase()
        val supported = when (asset.kind) {
            MediaAssetKind.AUDIO -> mime in supportedAudioMimeTypes
            MediaAssetKind.VIDEO -> mime in supportedVideoMimeTypes
            MediaAssetKind.IMAGE -> false
        }
        if (!supported) return null
        val bytes = file.readBytes()
        return ChatMessageMediaPart(
            kind = asset.kind.name,
            mimeType = mime,
            dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            estimatedTokens = estimatedTokens
        )
    }

    private fun prepareImage(context: Context, asset: MediaAsset): ChatMessageMediaPart? {
        val file = MediaProjectStore.assetFile(context, asset)
        if (!file.isFile || file.length() <= 0L || file.length() != asset.byteSize) return null
        val mime = asset.mimeType.lowercase()
        if (asset.rotationDegrees % 360 == 0 && file.length() <= MAX_IMAGE_BYTES &&
            mime in passThroughImageMimeTypes
        ) {
            val bytes = file.readBytes()
            return ChatMessageMediaPart(
                kind = "IMAGE",
                mimeType = mime,
                dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                estimatedTokens = 1_024
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largestEdge = max(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (largestEdge / sampleSize > MAX_IMAGE_EDGE * 2) sampleSize *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null

        var working: Bitmap = decoded
        var scaled: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            if (max(working.width, working.height) > MAX_IMAGE_EDGE) {
                val ratio = MAX_IMAGE_EDGE.toFloat() / max(working.width, working.height)
                scaled = Bitmap.createScaledBitmap(
                    working,
                    (working.width * ratio).roundToInt().coerceAtLeast(1),
                    (working.height * ratio).roundToInt().coerceAtLeast(1),
                    true
                )
                working = scaled
            }
            val rotation = ((asset.rotationDegrees % 360) + 360) % 360
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                rotated = Bitmap.createBitmap(working, 0, 0, working.width, working.height, matrix, true)
                working = rotated
            }
            var bytes = encodeJpeg(working, 84)
            if (bytes.size > MAX_IMAGE_BYTES) {
                val ratio = 1_200f / max(working.width, working.height)
                val smaller = Bitmap.createScaledBitmap(
                    working,
                    (working.width * ratio).roundToInt().coerceAtLeast(1),
                    (working.height * ratio).roundToInt().coerceAtLeast(1),
                    true
                )
                try {
                    bytes = encodeJpeg(smaller, 72)
                } finally {
                    if (smaller !== working) smaller.recycle()
                }
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            return ChatMessageMediaPart(
                kind = "IMAGE",
                mimeType = "image/jpeg",
                dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                estimatedTokens = 1_024
            )
        } finally {
            if (rotated != null && rotated !== decoded && rotated !== scaled) rotated.recycle()
            if (scaled != null && scaled !== decoded && scaled !== rotated) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
}
