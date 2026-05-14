package com.example.phonecam.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.LifecycleService
import com.example.phonecam.MainActivity
import com.example.phonecam.R
import com.example.phonecam.recorder.BitrateController
import com.example.phonecam.recorder.SegmentRecorder
import com.example.phonecam.storage.CircularStorageManager
import com.example.phonecam.streaming.HlsPackager
import com.example.phonecam.streaming.LocalHttpServer
import java.io.File

class RecordingService : LifecycleService() {

    companion object {
        const val HTTP_PORT = 8080
        private const val TAG = "RecordingService"
        private const val CH_ID = "phonecam_recording"
        private const val NID = 1001
        const val SEGMENT_DURATION_MS = 60_000L
        const val STORAGE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024 // 10 GB
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var storage: CircularStorageManager
    private lateinit var segmentRecorder: SegmentRecorder
    private lateinit var bitrateController: BitrateController
    private lateinit var hlsPackager: HlsPackager
    private lateinit var httpServer: LocalHttpServer

    private lateinit var videoDir: File
    private lateinit var hlsDir: File

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        startInForeground()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonecam:rec").apply { acquire() }

        videoDir = File(getExternalFilesDir(null), "videos").apply { mkdirs() }
        hlsDir = File(cacheDir, "hls").apply { mkdirs() }

        storage = CircularStorageManager(videoDir, STORAGE_LIMIT_BYTES)
        bitrateController = BitrateController()
        hlsPackager = HlsPackager(hlsDir)
        httpServer = LocalHttpServer(this, HTTP_PORT, hlsDir).also {
            try { it.start() } catch (e: Exception) { Log.e(TAG, "http start fail", e) }
        }

        val saveAudio = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("save_audio", true)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            segmentRecorder = SegmentRecorder(
                context = this,
                lifecycleOwner = this,
                cameraProvider = cameraProvider,
                videoDir = videoDir,
                segmentDurationMs = SEGMENT_DURATION_MS,
                saveAudio = saveAudio,
                bitrateController = bitrateController,
                onSegmentFinalized = { mp4 ->
                    storage.gc()
                    hlsPackager.appendSegment(mp4)
                    bitrateController.onSegmentFinalized(mp4)
                }
            )
            segmentRecorder.start(CameraSelector.DEFAULT_BACK_CAMERA)
        }, mainExecutor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        try { segmentRecorder.stop() } catch (_: Throwable) {}
        try { httpServer.stop() } catch (_: Throwable) {}
        try { hlsPackager.shutdown() } catch (_: Throwable) {}
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
