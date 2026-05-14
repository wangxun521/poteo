package com.example.phonecam.recorder

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * Enumerates all logical cameras exposed by the device and assigns each one a
 * short, stable id ("C0", "C1", ...) plus a human-readable label like
 * "后置 广角 (5.4mm)". Phones from most CN OEMs expose front/wide/tele as
 * separate logical cameras here.
 */
data class CameraEntry(
    val id: String,
    val label: String,
    val facing: String,    // "前置" or "后置" or "其它"
    val selector: CameraSelector
)

class CameraRegistry(provider: ProcessCameraProvider) {

    val entries: List<CameraEntry>

    init {
        val list = mutableListOf<CameraEntry>()
        provider.availableCameraInfos.forEachIndexed { idx, info ->
            list.add(toEntry(idx, info))
        }
        entries = list
    }

    fun find(id: String): CameraEntry? = entries.firstOrNull { it.id == id }

    fun defaultBack(): CameraEntry =
        entries.firstOrNull { it.facing == "后置" } ?: entries.first()

    @SuppressLint("UnsafeOptInUsageError")
    private fun toEntry(idx: Int, info: CameraInfo): CameraEntry {
        val facing = when (info.lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> "前置"
            CameraSelector.LENS_FACING_BACK -> "后置"
            else -> "其它"
        }
        val focal: Float? = try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
        } catch (_: Throwable) { null }
        val tag = focal?.let { f ->
            when {
                f < 3.5f -> "超广角"
                f < 5f -> "广角"
                f > 8f -> "长焦"
                else -> "标准"
            }
        } ?: ""
        val focalStr = focal?.let { "%.1fmm".format(it) } ?: ""
        val parts = listOf(facing, tag, focalStr).filter { it.isNotBlank() }
        val label = if (parts.isEmpty()) "摄像头 $idx" else parts.joinToString(" ")
        return CameraEntry(
            id = "C$idx",
            label = label,
            facing = facing,
            selector = info.cameraSelector
        )
    }
}
