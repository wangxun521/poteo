package com.example.phonecam.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

data class CameraEntry(
    val id: String,        // stable, e.g. "C0", "C1", "C2", "C3"
    val cam2Id: String,    // raw Camera2 ID, e.g. "0", "1", "2", "3"
    val label: String,
    val facing: String,    // "前置" | "后置" | "其它"
    val selector: CameraSelector
)

/**
 * Enumerates cameras from BOTH:
 *   - Camera2 CameraManager.cameraIdList (sees all physical IDs the OS knows about)
 *   - CameraX ProcessCameraProvider.availableCameraInfos (the subset CameraX exposes)
 *
 * Builds CameraSelector for each Camera2 ID. Some IDs (e.g. Mi 9's ultrawide and
 * tele) are hidden by CameraX by default; we still attempt to bind via a
 * cameraFilter — if hasCamera() returns false we keep the entry but mark it
 * "(可能不可用)" so the user knows.
 */
class CameraRegistry(context: Context, private val provider: ProcessCameraProvider) {

    private val tag = "CameraRegistry"
    val entries: List<CameraEntry>

    init {
        val list = mutableListOf<CameraEntry>()
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cam2Ids = try { cm.cameraIdList.toList() } catch (_: Throwable) { emptyList() }
        cam2Ids.forEachIndexed { idx, id ->
            try {
                val ch = cm.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    else -> "其它"
                }
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val tag = focal?.let { f ->
                    when {
                        f < 3.5f -> "超广角"
                        f < 5f -> "广角"
                        f > 8f -> "长焦"
                        else -> "标准"
                    }
                } ?: ""
                val focalStr = focal?.let { "%.1fmm".format(it) } ?: ""
                val selector = buildSelector(id)
                val available = try { provider.hasCamera(selector) } catch (_: Throwable) { false }
                val suffix = if (available) "" else " (CameraX 不可用)"
                val parts = listOf(facing, tag, focalStr).filter { it.isNotBlank() }
                val label = (if (parts.isEmpty()) "摄像头$idx" else parts.joinToString(" ")) + suffix
                list.add(CameraEntry("C$idx", id, label, facing, selector))
            } catch (t: Throwable) {
                Log.w(this.tag, "skip cam2 id=$id: ${t.message}")
            }
        }
        // Fallback: if Camera2 enumeration yielded nothing, fall back to CameraX
        if (list.isEmpty()) {
            provider.availableCameraInfos.forEachIndexed { idx, info ->
                list.add(toCameraXEntry(idx, info))
            }
        }
        entries = list
    }

    fun find(id: String): CameraEntry? = entries.firstOrNull { it.id == id }
    fun defaultBack(): CameraEntry =
        entries.firstOrNull { it.facing == "后置" && !it.label.contains("不可用") }
            ?: entries.firstOrNull { it.facing == "后置" }
            ?: entries.first()

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildSelector(cam2Id: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter {
                    try { Camera2CameraInfo.from(it).cameraId == cam2Id } catch (_: Throwable) { false }
                }
            }
            .build()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun toCameraXEntry(idx: Int, info: CameraInfo): CameraEntry {
        val facing = when (info.lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> "前置"
            CameraSelector.LENS_FACING_BACK -> "后置"
            else -> "其它"
        }
        val cam2Id = try { Camera2CameraInfo.from(info).cameraId } catch (_: Throwable) { "?" }
        val focal = try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
        } catch (_: Throwable) { null }
        val tag = focal?.let { f ->
            when { f < 3.5f -> "超广角"; f < 5f -> "广角"; f > 8f -> "长焦"; else -> "标准" }
        } ?: ""
        val focalStr = focal?.let { "%.1fmm".format(it) } ?: ""
        val parts = listOf(facing, tag, focalStr).filter { it.isNotBlank() }
        val label = if (parts.isEmpty()) "摄像头$idx" else parts.joinToString(" ")
        return CameraEntry("C$idx", cam2Id, label, facing, info.cameraSelector)
    }
}
