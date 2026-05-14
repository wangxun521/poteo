package com.example.phonecam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.phonecam.databinding.ActivityMainBinding
import com.example.phonecam.recorder.CameraEntry
import com.example.phonecam.recorder.StreamConfig
import com.example.phonecam.service.RecordingService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serviceBinder: RecordingService.LocalBinder? = null
    private var serviceBound = false
    private var cameras: List<CameraEntry> = emptyList()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBinder = service as? RecordingService.LocalBinder
            serviceBound = true
            refreshFromService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null; serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r -> if (r.values.all { it }) startService() else toast("权限被拒绝") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { ensurePermissionsAndStart() }
        binding.btnStop.setOnClickListener {
            unbindIfBound()
            stopService(Intent(this, RecordingService::class.java))
            binding.previewView.visibility = View.GONE
            toast("已停止"); refreshUrl()
        }
        binding.swAudio.setOnCheckedChangeListener { _, checked ->
            val cur = StreamConfig.load(this).copy(saveAudio = checked)
            StreamConfig.save(this, cur)
            serviceBinder?.applyConfig(cur)
        }
        binding.swPreview.setOnCheckedChangeListener { _, _ -> applyPreviewState() }

        val cfg = StreamConfig.load(this)
        binding.swAudio.isChecked = cfg.saveAudio

        refreshUrl()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecordingService::class.java), conn, 0)
    }

    override fun onStop() {
        super.onStop()
        unbindIfBound()
    }

    private fun unbindIfBound() {
        if (serviceBound) { try { unbindService(conn) } catch (_: Throwable) {} ; serviceBound = false; serviceBinder = null }
    }

    private fun refreshFromService() {
        val b = serviceBinder ?: return
        applyPreviewState()
        cameras = b.listCameras()
        if (cameras.isEmpty()) return
        val labels = cameras.map { it.label }
        binding.spCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val curId = b.currentCameraId()
        val idx = cameras.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        binding.spCamera.setSelection(idx, false)
        binding.spCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pick = cameras.getOrNull(position) ?: return
                if (pick.id != serviceBinder?.currentCameraId()) {
                    serviceBinder?.switchCamera(pick.id)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyPreviewState() {
        val want = binding.swPreview.isChecked
        binding.previewView.visibility = if (want) View.VISIBLE else View.GONE
        val provider = if (want) binding.previewView.surfaceProvider else null
        serviceBinder?.setPreviewSurfaceProvider(provider)
    }

    private fun refreshUrl() {
        val ip = getLocalIpAddress()
        val url = if (ip != null) "http://$ip:${RecordingService.HTTP_PORT}/" else "未连接 WiFi"
        binding.txtUrl.text = "预览地址: $url"
    }

    private fun ensurePermissionsAndStart() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        toast("已启动监控"); refreshUrl()
    }

    private fun getLocalIpAddress(): String? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifi.connectionInfo.ipAddress
        if (ipInt == 0) return null
        @Suppress("DEPRECATION") return Formatter.formatIpAddress(ipInt)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
