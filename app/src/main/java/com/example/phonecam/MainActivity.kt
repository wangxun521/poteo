package com.example.phonecam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.phonecam.databinding.ActivityMainBinding
import com.example.phonecam.service.RecordingService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) startService() else toast("权限被拒绝，无法录制")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener { ensurePermissionsAndStart() }
        binding.btnStop.setOnClickListener {
            stopService(Intent(this, RecordingService::class.java))
            toast("已停止")
            refreshUrl()
        }
        binding.swAudio.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("save_audio", checked).apply()
        }
        binding.swAudio.isChecked =
            getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("save_audio", true)

        refreshUrl()
    }

    private fun refreshUrl() {
        val ip = getLocalIpAddress()
        val url = if (ip != null) "http://$ip:${RecordingService.HTTP_PORT}/" else "未连接 WiFi"
        binding.txtUrl.text = "预览地址: $url"
    }

    private fun ensurePermissionsAndStart() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        toast("已启动监控")
        refreshUrl()
    }

    private fun getLocalIpAddress(): String? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifi.connectionInfo.ipAddress
        if (ipInt == 0) return null
        @Suppress("DEPRECATION")
        return Formatter.formatIpAddress(ipInt)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
