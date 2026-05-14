package com.example.phonecam.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import com.example.phonecam.MainActivity
import com.example.phonecam.recorder.BitrateController
import com.example.phonecam.recorder.CameraRegistry
import com.example.phonecam.recorder.SegmentRecorder
import com.example.phonecam.recorder.StreamConfig
import com.example.phonecam.storage.CircularStorageManager
import com.example.phonecam.streaming.LocalHttpServer
import com.example.phonecam.streaming.MjpegStreamer
import java.io.File

class RecordingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun isReady(): Boolean = ::segmentRecorder.isInitialized
        fun setPreviewSurfaceProvider(p: Preview.SurfaceProvider?) {
            if (::segmentRecorder.isInitialized) segmentRecorder.setPreviewSurfaceProvider(p)
        }
        fun listCameras(): List<com.example.phonecam.recorder.CameraEntry> =
            if (::cameraRegistry.isInitialized) cameraRegistry.entries else emptyList()
        fun currentCameraId(): String = currentCameraId
        fun config(): StreamConfig = currentConfig
        fun switchCamera(id: String): Boolean = applyConfig(currentConfig.copy(cameraId = id))
        fun applyConfig(cfg: StreamConfig): Boolean {
            if (!::segmentRecorder.isInitialized) return false
            val entry = cameraRegistry.find(cfg.cameraId) ?: cameraRegistry.defaultBack()
            return try {
                segmentRecorder.rebuild(entry.selector, cfg)
                currentCameraId = entry.id
                currentConfig = cfg.copy(cameraId = entry.id)
                StreamConfig.save(this@RecordingService, currentConfig)
                mjpegStreamer.quality = cfg.jpegQuality
                mjpegStreamer.targetFps = cfg.streamFps
                true
            } catch (t: Throwable) {
                Log.e(TAG, "applyConfig failed", t); false
            }
        }
        fun videoDir(): File = videoDir

        // Storage
        fun storageInfo(): Triple<Long, Long, Pair<Long, Int>> = Triple(
            storage.usedBytes(),
            storage.limitBytes,
            storage.deviceFreeBytes() to storage.fileCount()
        )
        fun setStorageLimitBytes(limit: Long) {
            val clamped = limit.coerceAtLeast(100L * 1024 * 1024)  // floor 100 MB
            storage.limitBytes = clamped
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putLong("storage_limit", clamped).apply()
            storage.gc()
        }

        // Zoom
        fun zoom(): FloatArray? {
            val z = segmentRecorder.zoomState() ?: return null
            return floatArrayOf(z.minZoomRatio, z.maxZoomRatio, z.zoomRatio)
        }
        fun setZoom(r: Float): Boolean = segmentRecorder.setZoomRatio(r)
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    companion object {
        const val HTTP_PORT = 8080
        private const val TAG = "RecordingService"
        private const val CH_ID = "phonecam_recording"
        private const val NID = 1001
        const val SEGMENT_DURATION_MS = 60_000L
        const val STORAGE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var storage: CircularStorageManager
    private lateinit var segmentRecorder: SegmentRecorder
    private lateinit var bitrateController: BitrateController
    private lateinit var mjpegStreamer: MjpegStreamer
    private lateinit var httpServer: LocalHttpServer
    private lateinit var cameraRegistry: CameraRegistry
    private lateinit var videoDir: File
    private var currentCameraId: String = ""
    private lateinit var currentConfig: StreamConfig

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        startInForeground()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonecam:rec").apply { acquire() }

        videoDir = File(getExternalFilesDir(null), "videos").apply { mkdirs() }
        val savedLimit = getSharedPreferences("cfg", MODE_PRIVATE)
            .getLong("storage_limit", STORAGE_LIMIT_BYTES)
        storage = CircularStorageManager(videoDir, savedLimit)
        bitrateController = BitrateController()
        currentConfig = StreamConfig.load(this)
        mjpegStreamer = MjpegStreamer(
            quality = currentConfig.jpegQuality,
            targetFps = currentConfig.streamFps
        )
        httpServer = LocalHttpServer(this, HTTP_PORT, mjpegStreamer, binder, videoDir).also {
            try { it.start() } catch (e: Exception) { Log.e(TAG, "http start fail", e) }
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraRegistry = CameraRegistry(cameraProvider)
            val entry = cameraRegistry.find(currentConfig.cameraId) ?: cameraRegistry.defaultBack()
            currentCameraId = entry.id
            currentConfig = currentConfig.copy(cameraId = entry.id)
            StreamConfig.save(this, currentConfig)

            segmentRecorder = SegmentRecorder(
                context = this,
                lifecycleOwner = this,
                cameraProvider = cameraProvider,
                videoDir = videoDir,
                segmentDurationMs = SEGMENT_DURATION_MS,
                bitrateController = bitrateController,
                mjpegAnalyzer = mjpegStreamer,
                onSegmentFinalized = { mp4 ->
                    storage.gc()
                    bitrateController.onSegmentFinalized(mp4)
                }
            )
            segmentRecorder.start(entry.selector, currentConfig)
        }, mainExecutor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        try { segmentRecorder.stop() } catch (_: Throwable) {}
        try { httpServer.stop() } catch (_: Throwable) {}
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CH_ID)
            .setContentTitle("PhoneCam 监控运行中")
            .setContentText("正在录制并提供局域网预览")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NID, notif)
        }
    }
}
