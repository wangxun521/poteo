package com.example.phonecam.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresPermission
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
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
import java.util.concurrent.Executors

/**
 * Holds the camera binding and rotates output every [segmentDurationMs]. Each
 * segment is a standalone MP4 so [com.example.phonecam.storage.CircularStorageManager]
 * can safely delete the oldest while we keep recording. Supports graceful
 * rebuild() so we can switch camera or change config mid-stream.
 */
class SegmentRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val cameraProvider: ProcessCameraProvider,
    private val videoDir: File,
    private val segmentDurationMs: Long,
    private val bitrateController: BitrateController,
    private val mjpegAnalyzer: ImageAnalysis.Analyzer?,
    private val onSegmentFinalized: (File) -> Unit
) {
    private val tag = "SegmentRecorder"
    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var currentRecording: Recording? = null
    private var currentFile: File? = null

    @Volatile private var currentSelector: CameraSelector? = null
    @Volatile private var currentConfig: StreamConfig? = null
    @Volatile private var pendingRebuild: Pair<CameraSelector, StreamConfig>? = null
    @Volatile private var pendingSurfaceProvider: Preview.SurfaceProvider? = null
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var rotating = false

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    fun start(selector: CameraSelector, cfg: StreamConfig) {
        currentSelector = selector
        currentConfig = cfg
        bindWith(selector, cfg)
        startNextSegment(cfg.saveAudio)
    }

    /** Re-bind to a new camera and/or apply new config. Safe to call mid-stream. */
    fun rebuild(selector: CameraSelector, cfg: StreamConfig) {
        if (currentRecording == null) {
            currentSelector = selector
            currentConfig = cfg
            bindWith(selector, cfg)
            startNextSegment(cfg.saveAudio)
            return
        }
        pendingRebuild = selector to cfg
        rotating = false
        main.removeCallbacksAndMessages(null)
        currentRecording?.stop()
        currentRecording = null
    }

    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        pendingSurfaceProvider = provider
        preview?.setSurfaceProvider(provider)
    }

    fun zoomState(): ZoomState? = camera?.cameraInfo?.zoomState?.value

    fun setZoomRatio(ratio: Float): Boolean {
        val cam = camera ?: return false
        return try {
            cam.cameraControl.setZoomRatio(ratio)
            true
        } catch (_: Throwable) { false }
    }

    @SuppressLint("MissingPermission")
    private fun bindWith(selector: CameraSelector, cfg: StreamConfig) {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(cfg.toQuality()))
            .setTargetVideoEncodingBitRate(cfg.recBitrate)
            .build()
        val capture = VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(cfg.recFps.coerceIn(1, 60), cfg.recFps.coerceIn(1, 60)))
            .build()
        val prev = Preview.Builder().build().also {
            pendingSurfaceProvider?.let(it::setSurfaceProvider)
        }
        val analysis: ImageAnalysis? = mjpegAnalyzer?.let { analyzer ->
            val resSel = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(cfg.streamWidth, cfg.streamHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resSel)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
        }
        try {
            cameraProvider.unbindAll()
            camera = if (analysis != null) cameraProvider.bindToLifecycle(lifecycleOwner, selector, capture, prev, analysis)
                    else cameraProvider.bindToLifecycle(lifecycleOwner, selector, capture, prev)
            videoCapture = capture
            preview = prev
            imageAnalysis = analysis
        } catch (t: Throwable) {
            Log.e(tag, "bindWith failed", t)
            throw t
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNextSegment(saveAudio: Boolean) {
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
                    if (event.hasError()) Log.w(tag, "finalize err=${event.error}")
                    if (finishedFile != null && finishedFile.exists() && finishedFile.length() > 0) {
                        try { onSegmentFinalized(finishedFile) } catch (t: Throwable) { Log.e(tag, "cb", t) }
                    }
                    // Priority: pending rebuild > pending rotation
                    val pr = pendingRebuild
                    if (pr != null) {
                        pendingRebuild = null
                        currentSelector = pr.first
                        currentConfig = pr.second
                        try {
                            bindWith(pr.first, pr.second)
                            startNextSegment(pr.second.saveAudio)
                        } catch (t: Throwable) {
                            Log.e(tag, "rebuild failed", t)
                        }
                    } else if (rotating) {
                        rotating = false
                        startNextSegment(currentConfig?.saveAudio ?: true)
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
        pendingRebuild = null
        main.removeCallbacksAndMessages(null)
        currentRecording?.stop()
        currentRecording = null
        try { cameraProvider.unbindAll() } catch (_: Throwable) {}
        analysisExecutor.shutdownNow()
    }
}
