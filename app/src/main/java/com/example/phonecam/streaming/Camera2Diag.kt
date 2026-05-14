package com.example.phonecam.streaming

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Dumps the raw Camera2 view of the device so we can diagnose hidden cameras.
 * Returns plain text (easier to read in a browser) covering:
 *   - top-level cameraIdList
 *   - each id's facing, focal lengths, physicalCameraIds set
 *   - per physical id: facing + focal lengths
 */
object Camera2Diag {

    fun dump(context: Context): String {
        val sb = StringBuilder()
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}  (API ${Build.VERSION.SDK_INT})\n")
        val cm = try {
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        } catch (t: Throwable) {
            sb.append("CameraManager unavailable: ${t.message}\n"); return sb.toString()
        }
        val ids = try { cm.cameraIdList.toList() } catch (t: Throwable) {
            sb.append("cameraIdList failed: ${t.message}\n"); return sb.toString()
        }
        sb.append("cameraIdList = $ids\n\n")
        for (id in ids) {
            val ch = try { cm.getCameraCharacteristics(id) } catch (t: Throwable) {
                sb.append("[$id] error: ${t.message}\n"); continue
            }
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString() else null
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()
            val isLogical = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) ?: false
            val physical: Set<String> = try { ch.physicalCameraIds } catch (_: Throwable) { emptySet() }
            sb.append("[$id] facing=$facing focal=$focals zoomRange=$zoomRange logicalMulti=$isLogical\n")
            sb.append("    physicalCameraIds = $physical\n")
            for (pid in physical) {
                val pch = try { cm.getCameraCharacteristics(pid) } catch (t: Throwable) {
                    sb.append("    [$pid] error: ${t.message}\n"); continue
                }
                val pFacing = when (pch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    else -> "?"
                }
                val pFocals = pch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
                sb.append("    [$pid] facing=$pFacing focal=$pFocals\n")
            }
        }
        return sb.toString()
    }
}
