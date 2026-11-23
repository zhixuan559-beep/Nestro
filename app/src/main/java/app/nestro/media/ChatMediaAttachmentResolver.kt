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
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Resolves the asset IDs already stored in the user's message into ephemeral native image parts.
 * The project media store remains the only owner of source files; message history keeps only the
 * existing asset_id text marker, so no second attachment store or database migration is needed.
 */
internal object ChatMediaAttachmentResolver {
    private const val MAX_IMAGE_EDGE = 1_600
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_IMAGE_BYTES = 12 * 1024 * 1024
    private val assetIdPattern = Regex("""asset_id=([A-Fa-f0-9-]{36})""")
    private val passThroughMimeTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    internal fun referencedAssetIds(content: String): List<String> =
        assetIdPattern.findAll(content).map { it.groupValues[1] }.distinct().toList()

    suspend fun resolve(
        context: Context,
        projectId: String,
        history: List<ChatMessage>
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (projectId.isBlank() || history.none { it.role == "user" && "asset_id=" in it.content }) {
            return@withContext history
        }
        val requestedIds = history.asReversed()
            .filter { it.role == "user" }
            .flatMap { referencedAssetIds(it.content) }
            .distinct()
        if (requestedIds.isEmpty()) return@withContext history

        val assets = runCatching {
            MediaProjectStore.listAssets(context, projectId).associateBy { it.id }
        }.getOrElse { return@withContext history }

        var remainingBytes = MAX_TOTAL_IMAGE_BYTES
        val prepared = linkedMapOf<String, ChatMessageMediaPart>()
        for (assetId in requestedIds) {
            if (remainingBytes <= 0) break
            val asset = assets[assetId] ?: continue
            if (asset.kind != MediaAssetKind.IMAGE) continue
            val part = runCatching { prepareImage(context, asset) }.getOrNull() ?: continue
            val byteCount = (part.dataBase64.length * 3L / 4L).toInt()
            if (byteCount !in 1..remainingBytes) continue
            prepared[assetId] = part
            remainingBytes -= byteCount
        }

        history.map { message ->
            if (message.role != "user") message
            else {
                val parts = referencedAssetIds(message.content).mapNotNull(prepared::get)
                if (parts.isEmpty()) message else message.copy(mediaParts = parts)
            }
        }
    }

    private fun prepareImage(context: Context, asset: MediaAsset): ChatMessageMediaPart? {
        val file = MediaProjectStore.assetFile(context, asset)
        if (!file.isFile || file.length() <= 0L || file.length() != asset.byteSize) return null
        val mime = asset.mimeType.lowercase()
        if (asset.rotationDegrees % 360 == 0 && file.length() <= MAX_IMAGE_BYTES &&
            mime in passThroughMimeTypes
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
