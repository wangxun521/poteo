package com.example.phonecam.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotates output every [segmentDurationMs]. Each segment is a standalone MP4
 * so [CircularStorageManager] can safely delete the oldest while we keep recording.
 */
class SegmentRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraProvider: ProcessCameraProvider,
    private val videoDir: File,
    private val segmentDurationMs: Long,
    private val saveAudio: Boolean,
    private val bitrateController: BitrateController,
    private val onSegmentFinalized: (File) -> Unit
) {
    private val tag = "SegmentRecorder"
    private val main = Handler(Looper.getMainLooper())
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var currentRecording: Recording? = null
    private var currentFile: File? = null
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var rotating = false

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    fun start(selector: CameraSelector) {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(bitrateController.targetQuality()))
            .setTargetVideoEncodingBitRate(bitrateController.currentBitrate())
            .build()
        val capture = VideoCapture.withOutput(recorder)
        val prev = Preview.Builder().build()
        videoCapture = capture
        preview = prev
        cameraProvider.unbindAll()
        // Bind both UseCases. setSurfaceProvider(null) by default => preview is
        // running but discarded; toggling provider on/off costs nothing and never
        // interrupts recording.
        cameraProvider.bindToLifecycle(lifecycleOwner, selector, capture, prev)
        startNextSegment()
    }

    /** Attach or detach a UI Surface for live preview. Pass null to disable. */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        preview?.setSurfaceProvider(provider)
    }

    @SuppressLint("MissingPermission")
    private fun startNextSegment() {
        val capture = videoCapture ?: return
        val file = File(videoDir, "${nameFmt.format(Date())}.mp4")
        currentFile = file
        val opts = FileOutputOptions.Builder(file).build()
        val pending = capture.output.prepareRecording(context, opts)
        if (saveAudio) pending.withAudioEnabled()

        currentRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    main.postDelayed({ rotate() }, segmentDurationMs)
                }
                is VideoRecordEvent.Finalize -> {
                    val finishedFile = currentFile
                    val hasErr = event.hasError()
                    if (hasErr) Log.w(tag, "finalize err=${event.error}")
                    if (finishedFile != null && finishedFile.exists() && finishedFile.length() > 0) {
                        try { onSegmentFinalized(finishedFile) } catch (t: Throwable) { Log.e(tag, "cb", t) }
                    }
                    if (rotating) {
                        rotating = false
                        startNextSegment()
                    }
                }
                else -> {}
            }
        }
    }

    private fun rotate() {
        if (currentRecording == null) return
        rotating = true
        currentRecording?.stop()
        currentRecording = null
    }

    fun stop() {
        rotating = false
        currentRecording?.stop()
        currentRecording = null
        try { cameraProvider.unbindAll() } catch (_: Throwable) {}
    }
}
