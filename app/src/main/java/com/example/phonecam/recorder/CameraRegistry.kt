@file:OptIn(ExperimentalCamera2Interop::class)

package com.example.phonecam.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * One selectable camera. If [physicalCam2Id] is non-null we bind to the parent
 * logical camera ([selector]) and pin each UseCase to this physical sub-camera
 * via Camera2Interop. This is how we reach the ultrawide on devices that hide
 * it from `cameraIdList` (e.g. Mi 9 / MIUI 12).
 */
data class CameraEntry(
    val id: String,
    val cam2Id: String,           // raw Camera2 id of the camera we bind to
    val physicalCam2Id: String?,  // null → use logical only; else pin a physical sub-camera
    val label: String,
    val facing: String,
    val focalMm: Float?,
    val selector: CameraSelector
)

@SuppressLint("UnsafeOptInUsageError")
class CameraRegistry(context: Context, provider: ProcessCameraProvider) {

    private val tag = "CameraRegistry"
    val entries: List<CameraEntry>

    init {
        val out = mutableListOf<CameraEntry>()
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cxInfos = provider.availableCameraInfos

        // 1) Each logical Camera2 ID exposed by CameraManager (always present)
        val topIds = try { cm.cameraIdList.toList() } catch (_: Throwable) { emptyList() }
        var counter = 0
        topIds.forEach { id ->
            val ch = try { cm.getCameraCharacteristics(id) } catch (_: Throwable) { return@forEach }
            val facing = facingFor(ch)
            val focal = focalFor(ch)
            val info = cxInfos.firstOrNull {
                try { Camera2CameraInfo.from(it).cameraId == id } catch (_: Throwable) { false }
            }
            val selector = info?.cameraSelector ?: buildSelectorById(id)
            val available = info != null
            val suffix = if (available) "" else " (CameraX 隐藏)"
            out.add(CameraEntry(
                id = "C${counter++}",
                cam2Id = id,
                physicalCam2Id = null,
                label = composeLabel(facing, focal) + suffix + "  · id=$id",
                facing = facing,
                focalMm = focal,
                selector = selector
            ))

            // 2) Physical sub-cameras of this logical (where the ultrawide/tele live on Mi 9).
            // Use Camera2 directly because CameraX 1.3 doesn't expose physicalCameraInfos.
            val physicalIds: Set<String> = try { ch.physicalCameraIds } catch (_: Throwable) { emptySet() }
            physicalIds.forEach pIds@{ pid ->
                if (pid == id) return@pIds
                val pch = try { cm.getCameraCharacteristics(pid) } catch (_: Throwable) { return@pIds }
                val pFocal = focalFor(pch)
                val pFacing = facingFor(pch)
                val selectorForPhys = info?.cameraSelector ?: selector
                out.add(CameraEntry(
                    id = "C${counter++}",
                    cam2Id = id,
                    physicalCam2Id = pid,
                    label = composeLabel(pFacing, pFocal) + "  · id=$id/$pid",
                    facing = pFacing,
                    focalMm = pFocal,
                    selector = selectorForPhys
                ))
            }
        }

        // Sort: back lenses first, then front, then 其它; within each group by focal length asc (ultrawide → tele).
        entries = out.sortedWith(
            compareBy(
                { when (it.facing) { "后置" -> 0; "前置" -> 1; else -> 2 } },
                { it.focalMm ?: 99f }
            )
        )

        Log.i(tag, "enumerated ${entries.size} cameras: " +
            entries.joinToString(", ") { "${it.id}=${it.label.substringBefore(" ·")}" })
    }

    fun find(id: String): CameraEntry? = entries.firstOrNull { it.id == id }

    fun defaultBack(): CameraEntry =
        entries.firstOrNull { it.facing == "后置" && it.physicalCam2Id == null && (it.focalMm ?: 0f) in 3.5f..7f }
            ?: entries.firstOrNull { it.facing == "后置" && it.physicalCam2Id == null }
            ?: entries.first()

    private fun facingFor(ch: CameraCharacteristics): String =
        when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            CameraCharacteristics.LENS_FACING_BACK -> "后置"
            else -> "其它"
        }

    private fun focalFor(ch: CameraCharacteristics): Float? =
        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

    private fun composeLabel(facing: String, focal: Float?): String {
        val tag = focal?.let { f ->
            when {
                f < 3.5f -> "超广角"
                f < 5f -> "广角"
                f > 8f -> "长焦"
                else -> "标准"
            }
        } ?: ""
        val mm = focal?.let { "%.1fmm".format(it) } ?: ""
        return listOf(facing, tag, mm).filter { it.isNotBlank() }.joinToString(" ")
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildSelectorById(id: String): CameraSelector =
        CameraSelector.Builder().addCameraFilter { infos ->
            infos.filter {
                try { Camera2CameraInfo.from(it).cameraId == id } catch (_: Throwable) { false }
            }
        }.build()
}
