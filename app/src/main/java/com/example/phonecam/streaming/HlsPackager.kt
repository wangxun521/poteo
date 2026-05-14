package com.example.phonecam.streaming

import android.content.Context
import android.util.Log
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler
import com.github.hiteshsondhi88.libffmpeg.FFmpeg
import com.github.hiteshsondhi88.libffmpeg.LoadBinaryResponseHandler
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegNotSupportedException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Maintains an HLS playlist (live.m3u8) and a sliding window of .ts segments in
 * [hlsDir]. Each finalized MP4 from SegmentRecorder is transmuxed (-c copy) into
 * one or more .ts pieces and appended to the playlist; the oldest beyond
 * [windowSize] are dropped.
 *
 * Uses writingminds/FFmpegAndroid (package com.github.hiteshsondhi88.libffmpeg)
 * because arthenica ffmpeg-kit / mobile-ffmpeg were removed from Maven Central
 * in 2025. Note: this fork bundles ffmpeg 3.0.1 with armv7-only binaries on
 * some ABIs — verify isSupported() at startup.
 */
class HlsPackager(
    private val context: Context,
    private val hlsDir: File,
    private val tsTargetSec: Int = 6,
    private val windowSize: Int = 6
) {
    private val tag = "HlsPackager"
    private val exec = Executors.newSingleThreadExecutor()
    private val seq = AtomicLong(0L)
    private val tsList = ArrayDeque<File>()
    private val playlist = File(hlsDir, "live.m3u8")

    private val ffmpeg: FFmpeg = FFmpeg.getInstance(context)
    @Volatile private var ready: Boolean = false

    init {
        hlsDir.listFiles()?.forEach { it.delete() }
        writePlaylist()
        try {
            ffmpeg.loadBinary(object : LoadBinaryResponseHandler() {
                override fun onStart() {}
                override fun onFinish() {}
                override fun onSuccess() { ready = true; Log.i(tag, "ffmpeg ready") }
                override fun onFailure() { Log.e(tag, "ffmpeg binary load failed") }
            })
        } catch (e: FFmpegNotSupportedException) {
            Log.e(tag, "ffmpeg not supported on this device ABI", e)
        }
    }

    fun appendSegment(mp4: File) {
        exec.submit {
            try { processSegment(mp4) } catch (t: Throwable) { Log.e(tag, "process fail", t) }
        }
    }

    private fun processSegment(mp4: File) {
        if (!ready) { Log.w(tag, "ffmpeg not ready, skipping ${mp4.name}"); return }
        val baseSeq = seq.get()
        val pattern = File(hlsDir, "seg-%05d.ts").absolutePath
        val tmpBefore = hlsDir.listFiles()?.toSet().orEmpty()
        val args = arrayOf(
            "-y", "-i", mp4.absolutePath,
            "-c", "copy", "-bsf:v", "h264_mp4toannexb",
            "-f", "segment", "-segment_time", tsTargetSec.toString(),
            "-segment_format", "mpegts",
            "-reset_timestamps", "1",
            "-start_number", baseSeq.toString(),
            pattern
        )
        val latch = CountDownLatch(1)
        var ok = false
        try {
            ffmpeg.execute(args, object : ExecuteBinaryResponseHandler() {
                override fun onStart() {}
                override fun onProgress(message: String?) {}
                override fun onSuccess(message: String?) { ok = true }
                override fun onFailure(message: String?) {
                    Log.w(tag, "ffmpeg fail: ${message?.take(400)}")
                }
                override fun onFinish() { latch.countDown() }
            })
        } catch (e: FFmpegCommandAlreadyRunningException) {
            Log.w(tag, "ffmpeg busy, skip ${mp4.name}")
            return
        }
        if (!latch.await(60, TimeUnit.SECONDS)) {
            Log.w(tag, "ffmpeg timeout for ${mp4.name}")
            return
        }
        if (!ok) return

        val newTs = (hlsDir.listFiles()?.toSet().orEmpty() - tmpBefore)
            .filter { it.name.startsWith("seg-") && it.name.endsWith(".ts") }
            .sortedBy { it.name }
        if (newTs.isEmpty()) return
        synchronized(this) {
            newTs.forEach { tsList.addLast(it) }
            seq.addAndGet(newTs.size.toLong())
            while (tsList.size > windowSize) {
                tsList.removeFirst().delete()
            }
            writePlaylist()
        }
    }

    private fun writePlaylist() {
        val mediaSeq = if (tsList.isEmpty()) 0L
            else tsList.first().name
                .removePrefix("seg-").removeSuffix(".ts")
                .trimStart('0').ifEmpty { "0" }.toLong()
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:${tsTargetSec + 1}\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:$mediaSeq\n")
        for (f in tsList) {
            sb.append("#EXTINF:${tsTargetSec}.0,\n")
            sb.append(f.name).append('\n')
        }
        playlist.writeText(sb.toString())
    }

    fun shutdown() {
        exec.shutdownNow()
    }
}
