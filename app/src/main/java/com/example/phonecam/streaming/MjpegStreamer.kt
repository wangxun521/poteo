package com.example.phonecam.streaming

import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Holds the most recent camera frame as a JPEG, plus a monotonically increasing
 * frame counter so HTTP serving threads can wait for a newer frame.
 *
 * Throttles capture rate to [targetFps] to keep CPU/bandwidth in check.
 */
class MjpegStreamer(
    @Volatile var quality: Int = 60,   // JPEG quality 1-100
    @Volatile var targetFps: Int = 10
) : ImageAnalysis.Analyzer {

    @Volatile var latest: ByteArray? = null
        private set
    private val frameId = AtomicLong(0L)
    fun frameSeq(): Long = frameId.get()

    private fun minIntervalMs(): Long = (1000 / targetFps.coerceIn(1, 60)).toLong()
    @Volatile private var lastProduced = 0L

    private val lock = Object()
    fun await(currentSeq: Long, timeoutMs: Long): ByteArray? {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (frameId.get() <= currentSeq) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
            return latest
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastProduced < minIntervalMs()) return
            val jpeg = encodeToJpeg(image) ?: return
            lastProduced = now
            latest = jpeg
            frameId.incrementAndGet()
            synchronized(lock) { lock.notifyAll() }
        } catch (_: Throwable) {
            // Drop frame on any error; analyzer must not crash the pipeline.
        } finally {
            image.close()
        }
    }

    private fun encodeToJpeg(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val baos = ByteArrayOutputStream()
        val ok = yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, baos)
        if (!ok) return null
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return baos.toByteArray()
        // Rotate to display orientation so the browser shows it upright.
        val src = baos.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return src
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        if (rotated !== bmp) rotated.recycle()
        return out.toByteArray()
    }

    /**
     * Convert ImageProxy (YUV_420_888) -> NV21 bytes.
     * Handles non-trivial pixel/row strides.
     */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val uvSize = w * h / 2
        val out = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Y
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == w) {
            yBuf.get(out, 0, ySize)
        } else {
            var pos = 0
            val row = ByteArray(w)
            for (r in 0 until h) {
                yBuf.position(r * yRowStride)
                yBuf.get(row, 0, w)
                System.arraycopy(row, 0, out, pos, w)
                pos += w
            }
        }

        // VU interleaved -> NV21 expects V then U at each chroma pixel
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        var offset = ySize
        val chromaH = h / 2
        val chromaW = w / 2
        val rowU = ByteArray(uvRowStride)
        val rowV = ByteArray(uvRowStride)
        for (r in 0 until chromaH) {
            uBuf.position(r * uvRowStride)
            vBuf.position(r * uvRowStride)
            val uReadable = minOf(uvRowStride, uBuf.remaining())
            val vReadable = minOf(uvRowStride, vBuf.remaining())
            uBuf.get(rowU, 0, uReadable)
            vBuf.get(rowV, 0, vReadable)
            for (c in 0 until chromaW) {
                out[offset++] = rowV[c * uvPixelStride]
                out[offset++] = rowU[c * uvPixelStride]
            }
        }
        return out
    }
}
