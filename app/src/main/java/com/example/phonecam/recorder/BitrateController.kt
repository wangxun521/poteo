package com.example.phonecam.recorder

import androidx.camera.video.Quality
import java.io.File

/**
 * Watches the rolling average file size of recently finalized segments and
 * suggests a target bitrate. The actual encoder bitrate is applied on the next
 * Recorder rebuild (we don't tear down per segment in this minimal version —
 * it picks up on app restart or quality change).
 *
 * Target: keep each 60s segment near [targetSegmentBytes] (~15 MB ≈ 2 Mbps).
 */
class BitrateController(
    private val targetSegmentBytes: Long = 15L * 1024 * 1024,
    private val minBitrate: Int = 500_000,
    private val maxBitrate: Int = 4_000_000
) {
    @Volatile private var bitrate: Int = 2_000_000
    @Volatile private var quality: Quality = Quality.HD
    private val history = ArrayDeque<Long>()

    fun currentBitrate(): Int = bitrate
    fun targetQuality(): Quality = quality

    @Synchronized
    fun onSegmentFinalized(file: File) {
        history.addLast(file.length())
        while (history.size > 5) history.removeFirst()
        if (history.size < 3) return
        val avg = history.average()
        val ratio = avg / targetSegmentBytes.toDouble()
        bitrate = when {
            ratio > 1.25 -> (bitrate * 0.85).toInt().coerceAtLeast(minBitrate)
            ratio < 0.75 -> (bitrate * 1.15).toInt().coerceAtMost(maxBitrate)
            else -> bitrate
        }
        quality = when {
            bitrate <= 800_000 -> Quality.SD
            bitrate >= 3_500_000 -> Quality.FHD
            else -> Quality.HD
        }
    }
}
