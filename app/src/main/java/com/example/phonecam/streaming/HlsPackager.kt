package com.example.phonecam.streaming

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Maintains an HLS playlist (live.m3u8) and a sliding window of .ts segments
 * inside [hlsDir]. Every finalized MP4 from SegmentRecorder is transmuxed (no
 * re-encode) into one or more .ts pieces using ffmpeg-kit and appended to the
 * playlist. The oldest .ts beyond [windowSize] segments are dropped.
 */
class HlsPackager(
    private val hlsDir: File,
    private val tsTargetSec: Int = 6,
    private val windowSize: Int = 6
) {
    private val tag = "HlsPackager"
    private val exec = Executors.newSingleThreadExecutor()
    private val seq = AtomicLong(0L)
    private val tsList = ArrayDeque<File>()
    private val playlist = File(hlsDir, "live.m3u8")

    init {
        hlsDir.listFiles()?.forEach { it.delete() }
        writePlaylist(initial = true)
    }

    fun appendSegment(mp4: File) {
        exec.submit {
            try { processSegment(mp4) } catch (t: Throwable) { Log.e(tag, "process fail", t) }
        }
    }

    private fun processSegment(mp4: File) {
        val baseSeq = seq.get()
        val pattern = File(hlsDir, "seg-%05d.ts").absolutePath
        // Transmux only; -bsf:v h264_mp4toannexb makes the AVC stream TS-compatible.
        // -hls_flags single_file=0 produces multiple .ts; we then move/rename them.
        val tmpListDirBefore = hlsDir.listFiles()?.toSet().orEmpty()
        val cmd = "-y -i \"${mp4.absolutePath}\" -c copy -bsf:v h264_mp4toannexb " +
            "-f segment -segment_time $tsTargetSec -segment_format mpegts " +
            "-reset_timestamps 1 -start_number ${baseSeq} \"$pattern\""
        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.w(tag, "ffmpeg failed rc=${session.returnCode} log=${session.allLogsAsString.take(500)}")
            return
        }
        val newTs = (hlsDir.listFiles()?.toSet().orEmpty() - tmpListDirBefore)
            .filter { it.name.startsWith("seg-") && it.name.endsWith(".ts") }
            .sortedBy { it.name }
        if (newTs.isEmpty()) return
        synchronized(this) {
            newTs.forEach { tsList.addLast(it) }
            seq.addAndGet(newTs.size.toLong())
            while (tsList.size > windowSize) {
                val drop = tsList.removeFirst()
                drop.delete()
            }
            writePlaylist(initial = false)
        }
    }

    private fun writePlaylist(initial: Boolean) {
        val mediaSeq = if (tsList.isEmpty()) 0
            else tsList.first().name.removePrefix("seg-").removeSuffix(".ts").trimStart('0').ifEmpty { "0" }.toLong()
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
