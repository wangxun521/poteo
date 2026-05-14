package com.example.phonecam.streaming

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/**
 * Reads thermal info from public sources available to non-root apps on Android 11+:
 *  - /sys/class/thermal/thermal_zone*\/{type,temp}  (each zone is named, temp in m°C)
 *  - BatteryManager (battery temp in deci-Celsius via BATTERY_PROPERTY or ACTION_BATTERY_CHANGED)
 *
 * Not all manufacturers expose every CPU core; some lock down sysfs entirely. Returns
 * whatever is readable. The caller can pick the hottest "CPU" zone heuristically.
 */
data class ThermalSnapshot(
    val zones: List<Zone>,
    val batteryC: Float?,
    val cpuC: Float?
) {
    data class Zone(val name: String, val tempC: Float)
}

class ThermalReader(private val context: Context) {

    private val thermalRoot = File("/sys/class/thermal")

    fun read(): ThermalSnapshot {
        val zones = mutableListOf<ThermalSnapshot.Zone>()
        try {
            thermalRoot.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
                ?.forEach { dir ->
                    val type = readTrim(File(dir, "type")) ?: dir.name
                    val raw = readTrim(File(dir, "temp"))?.toLongOrNull() ?: return@forEach
                    // Some kernels report deg, some milli-deg; > 200 means milli.
                    val c = if (raw > 200) raw / 1000f else raw.toFloat()
                    if (c in -40f..200f) zones.add(ThermalSnapshot.Zone(type, c))
                }
        } catch (_: Throwable) {}

        val batteryC = readBatteryTemp()

        // Pick the hottest plausible "CPU" zone as the headline number.
        val cpu = zones.filter { z ->
            val n = z.name.lowercase()
            n.contains("cpu") || n.contains("soc") || n.contains("tsens") || n.contains("apc")
        }.maxByOrNull { it.tempC }?.tempC

        return ThermalSnapshot(zones, batteryC, cpu)
    }

    private fun readTrim(f: File): String? = try {
        if (f.canRead()) f.readText().trim() else null
    } catch (_: Throwable) { null }

    private fun readBatteryTemp(): Float? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            // Not all Android versions expose battery temp via getIntProperty — fall back to sticky intent.
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val deciC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (deciC != null && deciC != Int.MIN_VALUE) deciC / 10f else null
        } catch (_: Throwable) { null }
    }

    fun toJson(): String {
        val s = read()
        val zonesJson = s.zones.joinToString(",") { z ->
            """{"name":"${esc(z.name)}","tempC":${"%.1f".format(z.tempC)}}"""
        }
        val cpu = s.cpuC?.let { "%.1f".format(it) } ?: "null"
        val bat = s.batteryC?.let { "%.1f".format(it) } ?: "null"
        return """{"cpuC":$cpu,"batteryC":$bat,"zones":[$zonesJson]}"""
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
