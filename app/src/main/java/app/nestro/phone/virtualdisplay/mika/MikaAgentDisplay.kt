/*
 * Adapted from Mika (https://github.com/wangzan101/Mika).
 * Copyright (C) 2026 wangzan101.
 * Licensed under the Apache License, Version 2.0.
 * Original: app/src/main/java/com/wangzan101/mika/cap/phone/display/AgentDisplay.kt
 * Adaptations: Nestro package namespace and narrow MikaVirtualDisplayShell interface.
 */
package app.nestro.phone.virtualdisplay.mika

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Dedicated virtual-screen frame receiver. Frames are capped at 10fps. */
class MikaAgentDisplay(
    private val shell: MikaVirtualDisplayShell,
    @Volatile private var bufferWidth: Int,
    @Volatile private var bufferHeight: Int,
    private var dpi: Int,
    private val displayManager: DisplayManager,
) {
    private val thread = HandlerThread("nestro-agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader = ImageReader.newInstance(bufferWidth, bufferHeight, PixelFormat.RGBA_8888, 2)

    val displayId: Int
    private val geometry get() = DisplayFrameGeometry(
        bufferWidth, bufferHeight,
        displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0
    )
    val width: Int get() = geometry.width
    val height: Int get() = geometry.height

    private val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null
    // Compose may still be drawing the previous StateFlow value after a new frame arrives.
    // Retire preview bitmaps briefly, then recycle them instead of leaking 10 new bitmaps/sec.
    private var previewFrame: Bitmap? = null
    private val retiredPreviewFrames = mutableSetOf<Bitmap>()
    private var hasFrame = false
    private var frameGeometry = DisplayFrameGeometry(bufferWidth, bufferHeight, Surface.ROTATION_0)
    private var lastProcessedAt = 0L
    private var pending = false
    private var frameTimestampNanos = 0L
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var previewLimit = 360

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        val id = try {
            shell.createDisplay(reader.surface, bufferWidth, bufferHeight, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
        reader.setOnImageAvailableListener({ schedule() }, handler)
        handler.post { schedule() }
    }

    /** Replace the frame surface without changing display ID or the app task on that display. */
    suspend fun resize(newWidth: Int, newHeight: Int, newDpi: Int): Boolean = suspendCancellableCoroutine { continuation ->
        if (released) { continuation.resume(false); return@suspendCoroutine }
        val posted = handler.post {
            if (!continuation.isActive) return@post
            if (released) { continuation.resume(false); return@post }
            val target = geometry.resized(newWidth, newHeight)
            if (bufferWidth == target.bufferWidth && bufferHeight == target.bufferHeight && dpi == newDpi) {
                if (continuation.isActive) continuation.resume(true); return@post
            }
            var next: ImageReader? = null
            val resized = try {
                next = ImageReader.newInstance(target.bufferWidth, target.bufferHeight, PixelFormat.RGBA_8888, 2)
                if (!shell.resizeDisplay(displayId, next.surface, target.bufferWidth, target.bufferHeight, newDpi)) false else {
                    reader.setOnImageAvailableListener(null, null)
                    reader.close()
                    synchronized(frameLock) {
                        previewLimit = 720
                        bufferWidth = target.bufferWidth
                        bufferHeight = target.bufferHeight
                        dpi = newDpi
                        fullFrame?.recycle()
                        fullFrame = null
                        hasFrame = false
                        frameTimestampNanos = 0L
                        _frames.value = null
                    }
                    reader = next
                    next = null
                    reader.setOnImageAvailableListener({ schedule() }, handler)
                    schedule()
                    true
                }
            } catch (_: Exception) { false }
            finally { next?.close() }
            if (continuation.isActive) continuation.resume(resized)
        }
        if (!posted && continuation.isActive) continuation.resume(false)
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            Log.w(TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}", t)
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                frameTimestampNanos = image.timestamp
                frameGeometry = capturedGeometry
                val nextPreview = uprightDisplayFrame(
                    bmp, capturedGeometry, capturedGeometry.previewWidth(previewLimit), paint
                )
                val previousPreview = previewFrame
                previewFrame = nextPreview
                _frames.value = nextPreview
                if (previousPreview != null) {
                    retiredPreviewFrames += previousPreview
                    handler.postDelayed({
                        synchronized(frameLock) {
                            if (retiredPreviewFrames.remove(previousPreview) && !previousPreview.isRecycled) {
                                previousPreview.recycle()
                            }
                        }
                    }, PREVIEW_RETIRE_DELAY_MS)
                }
            }
        } finally {
            image.close()
        }
    }

    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    fun lastFrameAt(): Long? = synchronized(frameLock) {
        frameTimestampNanos.takeIf { !released && it > 0 }
    }

    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else uprightDisplayFrame(bmp, frameGeometry, paint = paint)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacks(process)
        handler.post {
            reader.close()
            val retired = synchronized(frameLock) {
                val frames = retiredPreviewFrames.toList() + listOfNotNull(previewFrame, fullFrame)
                retiredPreviewFrames.clear()
                previewFrame = null
                fullFrame = null
                hasFrame = false
                frameTimestampNanos = 0L
                _frames.value = null
                frames.distinct()
            }
            // Let Compose finish the frame currently on screen before releasing its bitmap.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                retired.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            }, PREVIEW_RETIRE_DELAY_MS)
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "MikaAgentDisplay"
        const val FRAME_INTERVAL_MS = 100L
        const val PREVIEW_RETIRE_DELAY_MS = 500L
    }
}
