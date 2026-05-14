package com.example.phonecam.recorder

import android.content.Context
import androidx.camera.video.Quality

/**
 * All user-tunable parameters. Persisted to SharedPreferences. Changes are
 * applied on the next SegmentRecorder rebuild (camera switch or explicit
 * apply).
 */
data class StreamConfig(
    val cameraId: String,          // matches CameraEntry.id; "" = default back
    val saveAudio: Boolean,
    val recQuality: String,        // "SD" | "HD" | "FHD" | "UHD"
    val recBitrate: Int,           // bps
    val streamWidth: Int,
    val streamHeight: Int,
    val streamFps: Int,            // 1..30
    val jpegQuality: Int           // 1..100
) {
    fun toQuality(): Quality = when (recQuality.uppercase()) {
        "SD" -> Quality.SD
        "HD" -> Quality.HD
        "FHD" -> Quality.FHD
        "UHD" -> Quality.UHD
        else -> Quality.HD
    }

    fun toJson(): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"cameraId":"${esc(cameraId)}","saveAudio":$saveAudio,"recQuality":"${esc(recQuality)}","recBitrate":$recBitrate,"streamWidth":$streamWidth,"streamHeight":$streamHeight,"streamFps":$streamFps,"jpegQuality":$jpegQuality}"""
    }

    companion object {
        const val PREFS = "cfg"

        fun load(ctx: Context): StreamConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return StreamConfig(
                cameraId = p.getString("camera_id", "") ?: "",
                saveAudio = p.getBoolean("save_audio", true),
                recQuality = p.getString("rec_quality", "HD") ?: "HD",
                recBitrate = p.getInt("rec_bitrate", 2_000_000),
                streamWidth = p.getInt("stream_w", 640),
                streamHeight = p.getInt("stream_h", 480),
                streamFps = p.getInt("stream_fps", 10),
                jpegQuality = p.getInt("jpeg_q", 60)
            )
        }

        fun save(ctx: Context, c: StreamConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("camera_id", c.cameraId)
                putBoolean("save_audio", c.saveAudio)
                putString("rec_quality", c.recQuality)
                putInt("rec_bitrate", c.recBitrate)
                putInt("stream_w", c.streamWidth)
                putInt("stream_h", c.streamHeight)
                putInt("stream_fps", c.streamFps)
                putInt("jpeg_q", c.jpegQuality)
            }.apply()
        }
    }
}
